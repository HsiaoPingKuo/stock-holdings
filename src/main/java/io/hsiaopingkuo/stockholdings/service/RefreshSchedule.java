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
 * 每個市場各自套用（週一到週五）：
 * - 13:40～14:30：約每 60 秒一輪（60 秒 ± 0～5 秒，毫秒級隨機）
 * - 14:30～15:40：每 10 分鐘一輪（應付官方延遲公布）
 * - 15:40～23:40（G-03 延長時段）：今天還沒拿到資料、或還有待補查時，每 20 分鐘一輪。
 *   官方在兩輪之間公布，最晚約 20 分鐘加上一輪的執行時間就會拿到，中間失敗一輪也還在「公布後一小時內」
 * - 上一輪批次失敗改用逐檔：15:40 前改成每 10 分鐘一輪（逐檔請求多，太頻繁容易被擋）
 * - 今天已拿到資料、但還有持股的收盤價待補查（G-02）：15:40 前每 10 分鐘一輪，只做補查
 * - 冷卻中：等冷卻結束
 * - 今天已拿到資料而且沒有待補查、已過 23:40、週末：下一個平日 13:40
 */
public class RefreshSchedule {

	private final QuoteProperties.Refresh settings;
	private final RandomGenerator random;

	public RefreshSchedule(QuoteProperties.Refresh settings, RandomGenerator random) {
		this.settings = settings;
		this.random = random;
	}

	/** 現在這個時間，這個市場的排程輪該不該執行（13:40 前、23:40 後、週末、今天已完成都不執行）。 */
	public boolean shouldRun(ZonedDateTime now, MarketRefreshState state) {
		return isWeekday(now.toLocalDate()) && inWindow(now.toLocalTime())
				&& !finishedToday(state, now.toLocalDate()) && !state.isCoolingDown(now.toInstant());
	}

	/** 這一輪結束後，下一輪排在什麼時候。 */
	public ZonedDateTime nextRun(ZonedDateTime now, MarketRefreshState state) {
		if (state.isCoolingDown(now.toInstant())) {
			// 冷卻結束時如果還在時段內就那時候查，否則等下一個交易日
			return withinWindowOrNextDay(state.cooldownUntil().atZone(now.getZone()));
		}
		LocalDate today = now.toLocalDate();
		LocalTime time = now.toLocalTime();
		// 今天已完成的市場不會進到延長時段：延長時段只給還沒拿到資料或還有待補查的市場
		if (!isWeekday(today) || finishedToday(state, today) || !time.isBefore(settings.lateEndTime())) {
			return nextWeekdayStart(today.plusDays(1), now);
		}
		if (time.isBefore(settings.startTime())) {
			return now.with(settings.startTime());
		}
		return withinWindowOrNextDay(now.plus(intervalAt(time, state, today)));
	}

	/** 依時段決定間隔：15:40 後延長時段 20 分鐘；之前照原本的快速／慢速規則。 */
	private Duration intervalAt(LocalTime time, MarketRefreshState state, LocalDate today) {
		if (!time.isBefore(settings.endTime())) {
			return settings.lateInterval();
		}
		// 只剩補查收盤價時（今天已拿到資料）也用慢速：補查是逐檔請求，太頻繁容易被擋
		boolean slow = !time.isBefore(settings.slowdownTime()) || state.usedPerStock() || state.isDoneFor(today);
		return slow ? settings.slowInterval() : fastIntervalWithJitter();
	}

	/** 60 秒 ± 0～5 秒：用毫秒隨機，送出時間就不會剛好落在整數秒。 */
	private Duration fastIntervalWithJitter() {
		long jitterMillis = settings.fastJitter().toMillis();
		long offset = jitterMillis == 0 ? 0 : random.nextLong(-jitterMillis, jitterMillis + 1);
		return settings.fastInterval().plusMillis(offset);
	}

	/** 候選時間還在當天的時段內就用它；早於 13:40 改成 13:40；過了 23:40 或不是平日就改到下一個平日 13:40。 */
	private ZonedDateTime withinWindowOrNextDay(ZonedDateTime candidate) {
		LocalDate day = candidate.toLocalDate();
		if (isWeekday(day)) {
			if (candidate.toLocalTime().isBefore(settings.startTime())) {
				return candidate.with(settings.startTime());
			}
			if (candidate.toLocalTime().isBefore(settings.lateEndTime())) {
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

	/** 今天的資料拿到了，也沒有持股的收盤價待補查：這個市場今天不用再跑（G-02）。 */
	private static boolean finishedToday(MarketRefreshState state, LocalDate today) {
		return state.isDoneFor(today) && !state.hasPendingCloseBackfill(today);
	}

	private boolean inWindow(LocalTime time) {
		// 13:40～23:40；15:40 之後只有還沒完成的市場會跑（由 finishedToday 判斷）
		return !time.isBefore(settings.startTime()) && time.isBefore(settings.lateEndTime());
	}

	private static boolean isWeekday(LocalDate day) {
		DayOfWeek dayOfWeek = day.getDayOfWeek();
		return dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY;
	}
}
