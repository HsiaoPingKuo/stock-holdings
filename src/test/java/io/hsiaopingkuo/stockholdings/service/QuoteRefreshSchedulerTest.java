package io.hsiaopingkuo.stockholdings.service;

import static io.hsiaopingkuo.stockholdings.service.RefreshTestSupport.SETTINGS;
import static io.hsiaopingkuo.stockholdings.service.RefreshTestSupport.TAIPEI;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TimeZone;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.quote.HolidayCalendar;
import io.hsiaopingkuo.stockholdings.service.QuoteRefreshService.Trigger;
import io.hsiaopingkuo.stockholdings.service.RefreshTestSupport.MutableClock;

/**
 * 排程本身：啟動時排兩個市場、休市日不查、出錯也會排下一輪。
 * 查詢流程（QuoteRefreshService）、休市日表、TaskScheduler 都用假的，只檢查「排了什麼、呼叫了什麼」。
 */
class QuoteRefreshSchedulerTest {

	private MutableClock clock;
	private QuoteRefreshService refreshService;
	private HolidayCalendar holidayCalendar;
	private TaskScheduler taskScheduler;
	private final MarketRefreshState twseState = new MarketRefreshState(Market.TWSE);
	private final MarketRefreshState tpexState = new MarketRefreshState(Market.TPEX);
	/** 收到「啟動輪結束」通知的市場（第 6 題：除權息的啟動輪從這裡開始）。 */
	private final List<Market> startupFinished = new ArrayList<>();

	@BeforeEach
	void setUp() {
		clock = new MutableClock(LocalDateTime.of(2026, 11, 24, 13, 40));
		refreshService = mock(QuoteRefreshService.class);
		given(refreshService.state(Market.TWSE)).willReturn(twseState);
		given(refreshService.state(Market.TPEX)).willReturn(tpexState);
		holidayCalendar = mock(HolidayCalendar.class);
		taskScheduler = mock(TaskScheduler.class);
	}

	private QuoteRefreshScheduler scheduler(boolean enabled) {
		return new QuoteRefreshScheduler(refreshService, holidayCalendar, new RefreshSchedule(SETTINGS, new Random(1)),
				taskScheduler, clock, enabled, new MarketLocks(), startupFinished::add);
	}

	/** 排進 TaskScheduler 的時間。 */
	private List<Instant> scheduledTimes() {
		ArgumentCaptor<Instant> times = ArgumentCaptor.forClass(Instant.class);
		verify(taskScheduler, org.mockito.Mockito.atLeastOnce()).schedule(any(Runnable.class), times.capture());
		return new ArrayList<>(times.getAllValues());
	}

	private static Instant taipei(int day, int hour, int minute) {
		return LocalDateTime.of(2026, 11, day, hour, minute).atZone(TAIPEI).toInstant();
	}

	@Test
	void 排程關閉時_啟動後什麼都不排_直接通知除權息可以開始() {
		scheduler(false).onApplicationReady();

		verifyNoInteractions(taskScheduler, refreshService);
		assertThat(startupFinished).containsExactly(Market.TWSE, Market.TPEX);
	}

	@Test
	void 啟動時_兩個市場各排一個立即執行的啟動輪_不在啟動流程裡等官方() {
		scheduler(true).onApplicationReady();

		assertThat(scheduledTimes()).containsExactly(clock.instant(), clock.instant());
		// 只是排進背景，還沒有真的查
		verifyNoInteractions(refreshService);
	}

	@Test
	void 啟動輪不看休市日_一定查一次_做完排下一輪_並通知除權息() {
		scheduler(true).runStartupRound(Market.TWSE);

		verify(refreshService).runRound(Market.TWSE, Trigger.STARTUP);
		verifyNoInteractions(holidayCalendar);
		assertThat(scheduledTimes()).hasSize(1);
		assertThat(startupFinished).containsExactly(Market.TWSE);
	}

	@Test
	void 休市日_排程輪不查詢_當天完成_下一輪排到隔天1340() {
		given(holidayCalendar.isClosedToday()).willReturn(true);

		scheduler(true).runScheduledRound(Market.TWSE);

		verify(refreshService, never()).runRound(any(), any());
		assertThat(twseState.isDoneFor(LocalDate.of(2026, 11, 24))).isTrue();
		assertThat(scheduledTimes()).containsExactly(taipei(25, 13, 40));
	}

	@Test
	void 休市日表查不到時當成交易日_照常查詢() {
		// HolidayCalendar 查不到時回 false（當成交易日）
		given(holidayCalendar.isClosedToday()).willReturn(false);

		scheduler(true).runScheduledRound(Market.TWSE);

		verify(refreshService).runRound(Market.TWSE, Trigger.SCHEDULED);
	}

	@Test
	void 不在時段內被叫醒_不查詢也不問休市日表_只排下一輪() {
		clock = new MutableClock(LocalDateTime.of(2026, 11, 24, 13, 39));

		scheduler(true).runScheduledRound(Market.TWSE);

		verifyNoInteractions(holidayCalendar);
		verify(refreshService, never()).runRound(any(), any());
		assertThat(scheduledTimes()).containsExactly(taipei(24, 13, 40));
	}

	@Test
	void 查詢時發生例外_仍然排下一輪_排程不會停() {
		given(refreshService.runRound(Market.TPEX, Trigger.SCHEDULED)).willThrow(new IllegalStateException("模擬錯誤"));

		scheduler(true).runScheduledRound(Market.TPEX);

		assertThat(scheduledTimes()).hasSize(1);
	}

	@Test
	void 主機時區是UTC時_時段仍用台北時間判斷() {
		TimeZone original = TimeZone.getDefault();
		try {
			TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
			// UTC 05:39 = 台北 13:39：還沒到 13:40，下一輪是台北 13:40（UTC 05:40）
			clock = new MutableClock(Instant.parse("2026-11-24T05:39:00Z"));

			scheduler(true).runScheduledRound(Market.TWSE);

			verify(refreshService, never()).runRound(any(), any());
			assertThat(scheduledTimes()).containsExactly(Instant.parse("2026-11-24T05:40:00Z"));
		} finally {
			TimeZone.setDefault(original);
		}
	}
}
