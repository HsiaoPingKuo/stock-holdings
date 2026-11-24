package io.hsiaopingkuo.stockholdings.service;

import static io.hsiaopingkuo.stockholdings.service.RefreshTestSupport.SETTINGS;
import static io.hsiaopingkuo.stockholdings.service.RefreshTestSupport.TAIPEI;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import io.hsiaopingkuo.stockholdings.config.DividendSyncProperties;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.quote.HolidayCalendar;
import io.hsiaopingkuo.stockholdings.service.DividendSyncResult.Outcome;
import io.hsiaopingkuo.stockholdings.service.QuoteRefreshService.Trigger;
import io.hsiaopingkuo.stockholdings.service.RefreshTestSupport.MutableClock;

/**
 * 除權息排程（第 6 題第 4c 階段）：每日 07:30、休市日、冷卻中、補抓的判斷，和行情共用的市場鎖、冷卻。
 * 同步一輪（DividendSyncService）、休市日表、TaskScheduler 都用假的，只檢查「排了什麼、呼叫了什麼」。
 */
class DividendSyncSchedulerTest {

	private static final DividendSyncProperties DIVIDEND_SETTINGS = new DividendSyncProperties(true,
			LocalTime.of(7, 30), Duration.ofMinutes(30), Duration.ofMinutes(5));

	private MutableClock clock;
	private DividendSyncService syncService;
	private QuoteRefreshService refreshService;
	private HolidayCalendar holidayCalendar;
	private TaskScheduler taskScheduler;
	private final MarketLocks marketLocks = new MarketLocks();
	private final MarketRefreshState twseState = new MarketRefreshState(Market.TWSE);
	private final MarketRefreshState tpexState = new MarketRefreshState(Market.TPEX);

	@BeforeEach
	void setUp() {
		// 2026-11-24 是星期二
		clock = new MutableClock(LocalDateTime.of(2026, 11, 24, 7, 30));
		syncService = mock(DividendSyncService.class);
		given(syncService.syncMarket(any())).willAnswer(call -> result(call.getArgument(0), Outcome.COMPLETED));
		refreshService = mock(QuoteRefreshService.class);
		given(refreshService.state(Market.TWSE)).willReturn(twseState);
		given(refreshService.state(Market.TPEX)).willReturn(tpexState);
		holidayCalendar = mock(HolidayCalendar.class);
		taskScheduler = mock(TaskScheduler.class);
	}

	private DividendSyncScheduler scheduler() {
		return new DividendSyncScheduler(syncService, refreshService, holidayCalendar, marketLocks, taskScheduler,
				DIVIDEND_SETTINGS, SETTINGS.cooldown(), clock);
	}

	private static DividendSyncResult result(Market market, Outcome outcome) {
		return new DividendSyncResult(market, outcome, List.of(), List.of(), 0, 0, 0, 0, 0, 0);
	}

	/** 排進 TaskScheduler 的時間（schedule 的那種，不含補抓的固定間隔）。 */
	private List<Instant> scheduledTimes(TaskScheduler scheduler) {
		ArgumentCaptor<Instant> times = ArgumentCaptor.forClass(Instant.class);
		verify(scheduler, org.mockito.Mockito.atLeastOnce()).schedule(any(Runnable.class), times.capture());
		return times.getAllValues();
	}

	private static Instant taipei(int day, int hour, int minute) {
		return LocalDateTime.of(2026, 11, day, hour, minute).atZone(TAIPEI).toInstant();
	}

	private static ZonedDateTime taipeiTime(int day, int hour, int minute) {
		return LocalDateTime.of(2026, 11, day, hour, minute).atZone(TAIPEI);
	}

	// ===== 每日 07:30 =====

	@Test
	void 下一次每日輪_當天0730還沒到就是當天_已到或過了就是下一個平日_週五之後是週一() {
		DividendSyncScheduler scheduler = scheduler();

		assertThat(scheduler.nextDailyRun(taipeiTime(24, 6, 0))).isEqualTo(taipeiTime(24, 7, 30));
		assertThat(scheduler.nextDailyRun(taipeiTime(24, 7, 30))).isEqualTo(taipeiTime(25, 7, 30));
		// 11/27 是星期五
		assertThat(scheduler.nextDailyRun(taipeiTime(27, 8, 0))).isEqualTo(taipeiTime(30, 7, 30));
	}

	@Test
	void 交易日0730_同步一輪_排下一個交易日0730() {
		scheduler().runDailyRound(Market.TWSE);

		verify(syncService).syncMarket(Market.TWSE);
		assertThat(scheduledTimes(taskScheduler)).containsExactly(taipei(25, 7, 30));
	}

	@Test
	void 休市日_不同步_排下一個交易日0730() {
		given(holidayCalendar.isClosedToday()).willReturn(true);

		scheduler().runDailyRound(Market.TWSE);

		verify(syncService, never()).syncMarket(any());
		assertThat(scheduledTimes(taskScheduler)).containsExactly(taipei(25, 7, 30));
	}

	// ===== 冷卻中 =====

	@Test
	void 冷卻中_每日輪不同步_延後到冷卻結束() {
		twseState.startCooldown(taipei(24, 7, 50));

		scheduler().runDailyRound(Market.TWSE);

		verify(syncService, never()).syncMarket(any());
		assertThat(scheduledTimes(taskScheduler)).containsExactly(taipei(24, 7, 50));
	}

	@Test
	void 冷卻中_有缺的年份也不補抓_也不另外排() {
		twseState.startCooldown(taipei(24, 7, 50));
		given(syncService.hasMissingYears(Market.TWSE)).willReturn(true);

		scheduler().runBackfillCheck(Market.TWSE);

		verify(syncService, never()).syncMarket(any());
		verifyNoInteractions(taskScheduler);
	}

	// ===== 補抓 =====

	@Test
	void 補抓_沒有缺的年份_不同步也不發請求() {
		given(syncService.hasMissingYears(Market.TWSE)).willReturn(false);

		scheduler().runBackfillCheck(Market.TWSE);

		verify(syncService, never()).syncMarket(any());
		verifyNoInteractions(taskScheduler);
	}

	@Test
	void 補抓_有缺的年份_週末也同步一輪() {
		// 11/28 是星期六：補抓不看休市日
		clock = new MutableClock(LocalDateTime.of(2026, 11, 28, 10, 0));
		given(syncService.hasMissingYears(Market.TPEX)).willReturn(true);

		scheduler().runBackfillCheck(Market.TPEX);

		verify(syncService).syncMarket(Market.TPEX);
		verifyNoInteractions(holidayCalendar);
	}

	// ===== 和行情共用的市場鎖、冷卻 =====

	@Test
	void 市場鎖_行情正在跑時_同一市場的除權息不同步_行情跑完才拿得到() {
		clock = new MutableClock(LocalDateTime.of(2026, 11, 24, 13, 40));
		DividendSyncScheduler dividendScheduler = scheduler();
		given(syncService.hasMissingYears(Market.TWSE)).willReturn(true);
		// 行情查詢進行到一半時，除權息的補抓檢查剛好被叫醒
		given(refreshService.runRound(Market.TWSE, Trigger.SCHEDULED)).willAnswer(call -> {
			dividendScheduler.runBackfillCheck(Market.TWSE);
			return null;
		});
		QuoteRefreshScheduler quoteScheduler = new QuoteRefreshScheduler(refreshService, holidayCalendar,
				new RefreshSchedule(SETTINGS, new Random(1)), mock(TaskScheduler.class), clock, true, marketLocks,
				market -> {
				});

		quoteScheduler.runScheduledRound(Market.TWSE);

		verify(refreshService).runRound(Market.TWSE, Trigger.SCHEDULED);
		verify(syncService, never()).syncMarket(any());

		// 行情這一輪結束、放掉鎖之後，下一次檢查就能同步
		dividendScheduler.runBackfillCheck(Market.TWSE);

		verify(syncService, times(1)).syncMarket(Market.TWSE);
	}

	@Test
	void 除權息被擋_這個市場開始冷卻_行情排程也不查_另一個市場不受影響() {
		clock = new MutableClock(LocalDateTime.of(2026, 11, 24, 13, 40));
		given(syncService.hasMissingYears(Market.TWSE)).willReturn(true);
		given(syncService.syncMarket(Market.TWSE)).willReturn(result(Market.TWSE, Outcome.BLOCKED));

		scheduler().runBackfillCheck(Market.TWSE);

		// 冷卻時間沿用 quote.refresh.cooldown（30 分鐘）
		assertThat(twseState.cooldownUntil()).isEqualTo(taipei(24, 14, 10));
		assertThat(tpexState.isCoolingDown(clock.instant())).isFalse();

		TaskScheduler quoteTaskScheduler = mock(TaskScheduler.class);
		new QuoteRefreshScheduler(refreshService, holidayCalendar, new RefreshSchedule(SETTINGS, new Random(1)),
				quoteTaskScheduler, clock, true, marketLocks, market -> {
				}).runScheduledRound(Market.TWSE);

		verify(refreshService, never()).runRound(any(), any());
		// 行情的下一輪排在冷卻結束
		assertThat(scheduledTimes(quoteTaskScheduler)).containsExactly(taipei(24, 14, 10));
	}
}
