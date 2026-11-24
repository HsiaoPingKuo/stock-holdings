package io.hsiaopingkuo.stockholdings.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.TaskScheduler;

import io.hsiaopingkuo.stockholdings.config.DividendSyncProperties;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.quote.HolidayCalendar;

/**
 * 除權息同步的排程（第 6 題第 4c 階段）。每個市場三種觸發：
 * - 啟動：等那個市場「行情的啟動輪」結束才跑（QuoteRefreshScheduler 通知），兩邊不會同時打同一個市場
 * - 每日：每個交易日 07:30（台北時間）；休市日跳過
 * - 補抓：每 30 分鐘檢查一次，只有「有年份完全沒有紀錄」時才跑，平常只讀資料庫、不發請求
 * 每日和補抓都在啟動輪結束後才開始排。
 *
 * 和行情共用兩樣東西：
 * - MarketLocks：拿不到鎖代表行情或另一輪除權息正在跑 → 啟動輪、每日輪延後再試，補抓這次跳過
 * - MarketRefreshState 的冷卻：冷卻中不查；同步被擋時開始冷卻，行情排程也會跟著停
 *
 * 每一輪的摘要（年份、筆數、有沒有被擋）由 DividendSyncService.syncMarket 寫；這裡只寫來源和跳過、延後、冷卻的原因。
 */
public class DividendSyncScheduler implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(DividendSyncScheduler.class);

	enum Trigger {
		STARTUP("啟動"), DAILY("每日"), BACKFILL("補抓");

		private final String label;

		Trigger(String label) {
			this.label = label;
		}
	}

	private final DividendSyncService syncService;
	private final QuoteRefreshService refreshService;
	private final HolidayCalendar holidayCalendar;
	private final MarketLocks marketLocks;
	private final TaskScheduler taskScheduler;
	private final DividendSyncProperties settings;
	private final Duration cooldown;
	private final Clock clock;

	/**
	 * @param refreshService 只用來拿每個市場的 MarketRefreshState（冷卻狀態和行情共用）
	 * @param cooldown       被擋後冷卻多久（沿用 quote.refresh.cooldown）
	 */
	public DividendSyncScheduler(DividendSyncService syncService, QuoteRefreshService refreshService,
			HolidayCalendar holidayCalendar, MarketLocks marketLocks, TaskScheduler taskScheduler,
			DividendSyncProperties settings, Duration cooldown, Clock clock) {
		this.syncService = syncService;
		this.refreshService = refreshService;
		this.holidayCalendar = holidayCalendar;
		this.marketLocks = marketLocks;
		this.taskScheduler = taskScheduler;
		this.settings = settings;
		this.cooldown = cooldown;
		this.clock = clock;
	}

	/** 行情的啟動輪結束後呼叫（在行情的執行緒上）：只把除權息的啟動輪排進自己的執行緒就返回。 */
	public void onQuoteStartupFinished(Market market) {
		if (!settings.enabled()) {
			log.info("除權息排程已關閉（dividend.sync.enabled=false），{} 不同步", market);
			return;
		}
		taskScheduler.schedule(() -> runStartupRound(market), clock.instant());
	}

	/** 啟動輪：不看休市日。跑完（或確定不用跑）才開始排每日和補抓；被冷卻或鎖擋住就延後再試。 */
	void runStartupRound(Market market) {
		Optional<Instant> retryAt = Optional.empty();
		try {
			retryAt = attempt(market, Trigger.STARTUP);
		} catch (RuntimeException e) {
			log.warn("除權息排程 來源={} 市場={} 發生例外", Trigger.STARTUP.label, market, e);
		}
		if (retryAt.isPresent()) {
			schedule(() -> runStartupRound(market), retryAt.get(), market, Trigger.STARTUP);
			return;
		}
		scheduleNextDaily(market);
		Duration interval = settings.backfillCheckInterval();
		try {
			taskScheduler.scheduleWithFixedDelay(() -> runBackfillCheck(market), clock.instant().plus(interval),
					interval);
		} catch (RuntimeException e) {
			log.info("{} 無法排除權息補抓檢查（伺服器可能正在關閉）：{}", market, e.toString());
		}
	}

	/** 每日輪：休市日跳過；被冷卻或鎖擋住就延後，延後的那一次跑完才排下一個交易日。 */
	void runDailyRound(Market market) {
		try {
			if (holidayCalendar.isClosedToday()) {
				log.info("除權息排程 來源={} 市場={}：今天（{}）是休市日，不同步", Trigger.DAILY.label, market,
						LocalDate.now(clock));
			} else {
				Optional<Instant> retryAt = attempt(market, Trigger.DAILY);
				if (retryAt.isPresent()) {
					// 這裡 return 不排下一個交易日：延後的那一次跑完會排，避免同時有兩條每日的鏈
					schedule(() -> runDailyRound(market), retryAt.get(), market, Trigger.DAILY);
					return;
				}
			}
		} catch (RuntimeException e) {
			log.warn("除權息排程 來源={} 市場={} 發生例外", Trigger.DAILY.label, market, e);
		}
		scheduleNextDaily(market);
	}

	/** 補抓檢查：不看休市日；沒有缺的年份就什麼都不做（不寫 log、不發請求）。冷卻中或拿不到鎖就跳過，下次檢查再說。 */
	void runBackfillCheck(Market market) {
		try {
			if (!syncService.hasMissingYears(market)) {
				return;
			}
			attempt(market, Trigger.BACKFILL);
		} catch (RuntimeException e) {
			// 這是 scheduleWithFixedDelay 的工作：例外丟出去會讓之後的檢查全部停掉，所以一定要接住
			log.warn("除權息排程 來源={} 市場={} 發生例外", Trigger.BACKFILL.label, market, e);
		}
	}

	/**
	 * 看冷卻、拿鎖、同步一輪、被擋就開始冷卻。
	 *
	 * @return 要延後到什麼時候再試；empty 代表這一輪處理完了（跑了，或補抓決定跳過）
	 */
	private Optional<Instant> attempt(Market market, Trigger trigger) {
		MarketRefreshState state = refreshService.state(market);
		Instant now = clock.instant();
		if (state.isCoolingDown(now)) {
			if (trigger == Trigger.BACKFILL) {
				log.info("除權息排程 來源={} 市場={}：冷卻中（到 {}），這次跳過", trigger.label, market, state.cooldownUntil());
				return Optional.empty();
			}
			log.info("除權息排程 來源={} 市場={}：冷卻中，延後到冷卻結束 {}", trigger.label, market, state.cooldownUntil());
			return Optional.of(state.cooldownUntil());
		}
		if (!marketLocks.tryAcquire(market)) {
			if (trigger == Trigger.BACKFILL) {
				log.info("除權息排程 來源={} 市場={}：行情或另一輪除權息正在跑，這次跳過", trigger.label, market);
				return Optional.empty();
			}
			Instant retryAt = now.plus(settings.retryDelay());
			log.info("除權息排程 來源={} 市場={}：行情或另一輪除權息正在跑，延後到 {}", trigger.label, market, retryAt);
			return Optional.of(retryAt);
		}
		try {
			log.info("除權息排程 來源={} 市場={}：開始同步", trigger.label, market);
			DividendSyncResult result = syncService.syncMarket(market);
			if (result.blocked()) {
				// 和行情共用同一份狀態：行情的排程也會看到冷卻，這段時間兩邊都不查這個市場
				Instant until = clock.instant().plus(cooldown);
				state.startCooldown(until);
				log.warn("除權息排程 來源={} 市場={}：被官方擋下，這個市場暫停 {}，冷卻到 {}", trigger.label, market, cooldown, until);
			}
		} finally {
			marketLocks.release(market);
		}
		return Optional.empty();
	}

	private void scheduleNextDaily(Market market) {
		ZonedDateTime next = nextDailyRun(ZonedDateTime.now(clock));
		schedule(() -> runDailyRound(market), next.toInstant(), market, Trigger.DAILY);
	}

	/** 下一個平日的 dailyTime（嚴格晚於 now）。休市日在執行時才判斷，因為休市日表一天才查一次。 */
	ZonedDateTime nextDailyRun(ZonedDateTime now) {
		ZonedDateTime candidate = now.with(settings.dailyTime());
		if (!candidate.isAfter(now)) {
			candidate = candidate.plusDays(1);
		}
		while (candidate.getDayOfWeek() == DayOfWeek.SATURDAY || candidate.getDayOfWeek() == DayOfWeek.SUNDAY) {
			candidate = candidate.plusDays(1);
		}
		return candidate;
	}

	private void schedule(Runnable task, Instant at, Market market, Trigger trigger) {
		try {
			taskScheduler.schedule(task, at);
			log.info("除權息排程 來源={} 市場={}：下一次 {}", trigger.label, market, at.atZone(clock.getZone()).toLocalDateTime());
		} catch (RuntimeException e) {
			// 伺服器關閉中，排程器已停止時會拒絕新工作，這是正常的
			log.info("{} 無法排除權息同步（伺服器可能正在關閉）：{}", market, e.toString());
		}
	}

	/** 伺服器關閉時停止排程執行緒；正在等待的請求間隔會被中斷，同步回報 INTERRUPTED 後結束。 */
	@Override
	public void close() throws Exception {
		if (taskScheduler instanceof DisposableBean disposable) {
			disposable.destroy();
		}
	}
}
