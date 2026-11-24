package io.hsiaopingkuo.stockholdings.quote;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** 休市日判斷：一天最多查一次、查不到當交易日、用台北時間的「今天」。用假 client，不連網。 */
class HolidayCalendarTest {

	private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

	/** 可以手動往前撥的時鐘。 */
	private static final class MutableClock extends Clock {
		private Instant now;

		MutableClock(LocalDateTime taipeiTime) {
			this.now = taipeiTime.atZone(TAIPEI).toInstant();
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return TAIPEI;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	/** 回固定結果、會記錄被呼叫幾次的假 client。 */
	private static HolidayScheduleClient countingClient(AtomicInteger calls, Optional<Set<LocalDate>> answer) {
		return () -> {
			calls.incrementAndGet();
			return answer;
		};
	}

	@Test
	void 休市日當天_判斷為休市() {
		AtomicInteger calls = new AtomicInteger();
		MutableClock clock = new MutableClock(LocalDateTime.of(2027, 2, 2, 13, 40));
		HolidayCalendar calendar = new HolidayCalendar(
				countingClient(calls, Optional.of(Set.of(LocalDate.of(2027, 2, 2)))), clock);

		assertThat(calendar.isClosedToday()).isTrue();
	}

	@Test
	void 同一天問很多次_只查一次休市日表_隔天才重查() {
		AtomicInteger calls = new AtomicInteger();
		MutableClock clock = new MutableClock(LocalDateTime.of(2027, 2, 1, 13, 40));
		HolidayCalendar calendar = new HolidayCalendar(
				countingClient(calls, Optional.of(Set.of(LocalDate.of(2027, 2, 2)))), clock);

		assertThat(calendar.isClosedToday()).isFalse();
		clock.advance(Duration.ofMinutes(1));
		calendar.isClosedToday();
		clock.advance(Duration.ofMinutes(10));
		calendar.isClosedToday();
		assertThat(calls.get()).isEqualTo(1);

		clock.advance(Duration.ofDays(1));
		assertThat(calendar.isClosedToday()).isTrue();
		assertThat(calls.get()).isEqualTo(2);
	}

	@Test
	void 休市日表查不到_當成交易日_當天也不重查() {
		AtomicInteger calls = new AtomicInteger();
		MutableClock clock = new MutableClock(LocalDateTime.of(2027, 2, 2, 13, 40));
		HolidayCalendar calendar = new HolidayCalendar(countingClient(calls, Optional.empty()), clock);

		assertThat(calendar.isClosedToday()).isFalse();
		clock.advance(Duration.ofMinutes(1));
		assertThat(calendar.isClosedToday()).isFalse();
		assertThat(calls.get()).isEqualTo(1);
	}

	@Test
	void client意外丟例外_當成交易日_不往外丟() {
		HolidayCalendar calendar = new HolidayCalendar(() -> {
			throw new IllegalStateException("模擬程式錯誤");
		}, new MutableClock(LocalDateTime.of(2027, 2, 2, 13, 40)));

		assertThat(calendar.isClosedToday()).isFalse();
	}

	@Test
	void 表裡只有明年的資料_今年的日子當成交易日() {
		// 實測：2026 年 11 月官方只提供 2027 年的表
		HolidayCalendar calendar = new HolidayCalendar(
				countingClient(new AtomicInteger(), Optional.of(Set.of(LocalDate.of(2027, 1, 1)))),
				new MutableClock(LocalDateTime.of(2026, 12, 25, 13, 40)));

		assertThat(calendar.isClosedToday()).isFalse();
	}

	@Test
	void 今天用台北時間判斷_不受主機時區影響() {
		// UTC 2027-02-01 17:00 = 台北 2027-02-02 01:00，台北的今天是休市日
		Clock utcInstantTaipeiZone = Clock.fixed(Instant.parse("2027-02-01T17:00:00Z"), TAIPEI);
		HolidayCalendar calendar = new HolidayCalendar(
				countingClient(new AtomicInteger(), Optional.of(Set.of(LocalDate.of(2027, 2, 2)))), utcInstantTaipeiZone);

		assertThat(calendar.isClosedToday()).isTrue();
	}
}
