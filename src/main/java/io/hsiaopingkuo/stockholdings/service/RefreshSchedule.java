package io.hsiaopingkuo.stockholdings.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.random.RandomGenerator;

import io.hsiaopingkuo.stockholdings.config.QuoteProperties;

/**
 * 排程的時間規則（純計算，不碰網路和資料庫，所以很好測）。傳進來的時間一律是台北時間。
 *
 * 每個市場各自套用：
 * - 週一到週五 13:40～14:30：約每 60 秒一輪（60 秒 ± 0～5 秒，毫秒級隨機）
 * - 14:30～15:40：每 10 分鐘一輪（應付官方延遲公布）
 * - 上一輪批次失敗改用逐檔：這個市場改成每 10 分鐘一輪（逐檔請求多，太頻繁容易被擋）
 * - 冷卻中：等冷卻結束
 * - 今天已拿到資料、已過 15:40、週末：下一個平日 13:40
 */
public class RefreshSchedule {

	private final QuoteProperties.Refresh settings;
	private final RandomGenerator random;

	public RefreshSchedule(QuoteProperties.Refresh settings, RandomGenerator random) {
		this.settings = settings;
		this.random = random;
	}

	/** 現在這個時間，這個市場的排程輪該不該執行（13:40 前、15:40 後、週末、今天已完成都不執行）。 */
	public boolean shouldRun(ZonedDateTime now, MarketRefreshState state) {
		return isWeekday(now.toLocalDate()) && inWindow(now.toLocalTime()) && !state.isDoneFor(now.toLocalDate())
				&& !state.isCoolingDown(now.toInstant());
	}

	/** 這一輪結束後，下一輪排在什麼時候。 */
	public ZonedDateTime nextRun(ZonedDateTime now, MarketRefreshState state) {
		if (state.isCoolingDown(now.toInstant())) {
			// 冷卻結束時如果還在時段內就那時候查，否則等下一個交易日
			return withinWindowOrNextDay(state.cooldownUntil().atZone(now.getZone()));
		}
		LocalDate today = now.toLocalDate();
		LocalTime time = now.toLocalTime();
		if (!isWeekday(today) || state.isDoneFor(today) || !time.isBefore(settings.endTime())) {
			return nextWeekdayStart(today.plusDays(1), now);
		}
		if (time.isBefore(settings.startTime())) {
			return now.with(settings.startTime());
		}
		boolean slow = !time.isBefore(settings.slowdownTime()) || state.usedPerStock();
		Duration interval = slow ? settings.slowInterval() : fastIntervalWithJitter();
		return withinWindowOrNextDay(now.plus(interval));
	}

	/** 60 秒 ± 0～5 秒：用毫秒隨機，送出時間就不會剛好落在整數秒。 */
	private Duration fastIntervalWithJitter() {
		long jitterMillis = settings.fastJitter().toMillis();
		long offset = jitterMillis == 0 ? 0 : random.nextLong(-jitterMillis, jitterMillis + 1);
		return settings.fastInterval().plusMillis(offset);
	}

	/** 候選時間還在當天的時段內就用它；早於 13:40 改成 13:40；過了 15:40 或不是平日就改到下一個平日 13:40。 */
	private ZonedDateTime withinWindowOrNextDay(ZonedDateTime candidate) {
		LocalDate day = candidate.toLocalDate();
		if (isWeekday(day)) {
			if (candidate.toLocalTime().isBefore(settings.startTime())) {
				return candidate.with(settings.startTime());
			}
			if (candidate.toLocalTime().isBefore(settings.endTime())) {
				return candidate;
			}
		}
		return nextWeekdayStart(day.plusDays(1), candidate);
	}

	/** from（含）之後第一個平日的 13:40。 */
	private ZonedDateTime nextWeekdayStart(LocalDate from, ZonedDateTime zoneSource) {
		LocalDate day = from;
		while (!isWeekday(day)) {
			day = day.plusDays(1);
		}
		return day.atTime(settings.startTime()).atZone(zoneSource.getZone());
	}

	private boolean inWindow(LocalTime time) {
		return !time.isBefore(settings.startTime()) && time.isBefore(settings.endTime());
	}

	private static boolean isWeekday(LocalDate day) {
		DayOfWeek dayOfWeek = day.getDayOfWeek();
		return dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY;
	}
}
