package io.hsiaopingkuo.stockholdings.service;

import static io.hsiaopingkuo.stockholdings.service.RefreshTestSupport.SETTINGS;
import static io.hsiaopingkuo.stockholdings.service.RefreshTestSupport.TAIPEI;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import io.hsiaopingkuo.stockholdings.entity.Market;

/** 排程的時間規則。全部用固定時間計算，不真的等待。2026-11-24 是週二、11-27 是週五。 */
class RefreshScheduleTest {

	private final RefreshSchedule schedule = new RefreshSchedule(SETTINGS, new Random(7));
	private final MarketRefreshState state = new MarketRefreshState(Market.TWSE);

	private static ZonedDateTime taipei(int month, int day, int hour, int minute) {
		return LocalDateTime.of(2026, month, day, hour, minute).atZone(TAIPEI);
	}

	@Test
	void 收盤後1339不執行_下一輪排在1340() {
		ZonedDateTime now = taipei(11, 24, 13, 39);

		assertThat(schedule.shouldRun(now, state)).isFalse();
		assertThat(schedule.nextRun(now, state)).isEqualTo(taipei(11, 24, 13, 40));
	}

	@Test
	void 早上啟動_下一輪排在當天1340() {
		assertThat(schedule.nextRun(taipei(11, 24, 9, 0), state)).isEqualTo(taipei(11, 24, 13, 40));
	}

	@Test
	void 到了1340開始執行_下一輪約60秒後_加減5秒_不是整數秒() {
		ZonedDateTime now = taipei(11, 24, 13, 40);
		assertThat(schedule.shouldRun(now, state)).isTrue();

		List<Duration> intervals = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			intervals.add(Duration.between(now, schedule.nextRun(now, state)));
		}

		assertThat(intervals).allSatisfy(interval -> {
			assertThat(interval).isGreaterThanOrEqualTo(Duration.ofSeconds(55));
			assertThat(interval).isLessThanOrEqualTo(Duration.ofSeconds(65));
		});
		// 毫秒級隨機：不會每次都剛好是整數秒
		assertThat(intervals).anySatisfy(interval -> assertThat(interval.toMillisPart()).isNotZero());
		assertThat(intervals).doesNotHaveDuplicates();
	}

	@Test
	void 過了1430間隔變成10分鐘() {
		assertThat(schedule.nextRun(taipei(11, 24, 14, 30), state)).isEqualTo(taipei(11, 24, 14, 40));
		assertThat(schedule.nextRun(taipei(11, 24, 15, 0), state)).isEqualTo(taipei(11, 24, 15, 10));
	}

	@Test
	void 上一輪改用逐檔_快速時段也改成10分鐘() {
		state.setUsedPerStock(true);

		assertThat(schedule.nextRun(taipei(11, 24, 13, 50), state)).isEqualTo(taipei(11, 24, 14, 0));
	}

	@Test
	void 下一輪會超過1540_改到隔天1340() {
		assertThat(schedule.nextRun(taipei(11, 24, 15, 35), state)).isEqualTo(taipei(11, 25, 13, 40));
	}

	@Test
	void 到了1540停止_下一輪是隔天1340() {
		ZonedDateTime now = taipei(11, 24, 15, 40);

		assertThat(schedule.shouldRun(now, state)).isFalse();
		assertThat(schedule.nextRun(now, state)).isEqualTo(taipei(11, 25, 13, 40));
	}

	@Test
	void 今天已拿到資料_當天停止_下一輪是隔天1340() {
		state.markDone(LocalDate.of(2026, 11, 24));
		ZonedDateTime now = taipei(11, 24, 13, 55);

		assertThat(schedule.shouldRun(now, state)).isFalse();
		assertThat(schedule.nextRun(now, state)).isEqualTo(taipei(11, 25, 13, 40));
	}

	@Test
	void 週六週日不執行_週五收盤後下一輪是週一1340() {
		assertThat(schedule.shouldRun(taipei(11, 28, 14, 0), state)).isFalse();
		assertThat(schedule.shouldRun(taipei(11, 29, 14, 0), state)).isFalse();
		assertThat(schedule.nextRun(taipei(11, 27, 15, 40), state)).isEqualTo(taipei(11, 30, 13, 40));
		assertThat(schedule.nextRun(taipei(11, 28, 10, 0), state)).isEqualTo(taipei(11, 30, 13, 40));
	}

	@Test
	void 冷卻中不執行_冷卻結束時還在時段內_就排在冷卻結束時() {
		ZonedDateTime now = taipei(11, 24, 14, 0);
		state.startCooldown(now.plusMinutes(30).toInstant());

		assertThat(schedule.shouldRun(now, state)).isFalse();
		assertThat(schedule.nextRun(now, state)).isEqualTo(taipei(11, 24, 14, 30));
	}

	@Test
	void 冷卻結束時已過1540_排到隔天1340() {
		ZonedDateTime now = taipei(11, 24, 15, 20);
		state.startCooldown(now.plusMinutes(30).toInstant());

		assertThat(schedule.nextRun(now, state)).isEqualTo(taipei(11, 25, 13, 40));
	}
}
