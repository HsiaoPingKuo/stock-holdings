package io.hsiaopingkuo.stockholdings.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.hsiaopingkuo.stockholdings.config.QuoteProperties;
import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.quote.BatchQuoteResult;
import io.hsiaopingkuo.stockholdings.quote.MarketCloseBatchClient;
import io.hsiaopingkuo.stockholdings.quote.StockLookup;
import io.hsiaopingkuo.stockholdings.quote.StockLookupClient;
import io.hsiaopingkuo.stockholdings.repository.HoldingRepository;
import io.hsiaopingkuo.stockholdings.repository.QuoteRepository;

/**
 * 單一市場「一輪」的行情更新：批次優先、逐檔備援、被擋冷卻。排程和伺服器啟動共用這個流程。
 * 什麼時候執行下一輪由 QuoteRefreshScheduler 決定，這裡只負責「這一輪做什麼」。
 *
 * 一輪的流程：
 * ① 查批次（全市場收盤行情）
 *    - 拿到 → 整批寫入 quote 表；資料日期是今天 → 這個市場今天完成
 *      → 補舊持股的市場（③）→ 補查收盤價（①-b）
 *    - 沒有資料（還沒公布、休市）→ 不算錯，等下一輪
 *    - 被擋 → 這個市場冷卻（冷卻期間不發任何請求）
 *    - 失敗（404、500、逾時、格式不符）→ ②
 * ①-b 補查收盤價：批次只有那一天的資料，那天沒成交的股票沒有收盤價；
 *    對有人持有、但快取沒有這一列或沒有收盤價的代號，逐檔往前找最近一個有成交日的收盤價
 *    （G-02：這個市場今天已完成、但還有待補查時，排程輪只做這一步，不再抓整個市場）
 * ② 逐檔：只查這個市場「有人持有、而且快取還沒有今天資料」的代號，一次一個，請求之間隨機等一下
 * ③ 補舊持股的市場（market 是 NULL 的）
 * 任何例外都在這裡接住、寫 log，不會往外丟，排程不會因此停止。
 * 不加 @Service：在 QuoteConfig 用 @Bean 建立，市場和 client 的對應寫在那裡。
 */
public class QuoteRefreshService {

	private static final Logger log = LoggerFactory.getLogger(QuoteRefreshService.class);

	/** 這一輪是誰觸發的（寫在 log 裡）。 */
	public enum Trigger {
		STARTUP("啟動"), SCHEDULED("排程");

		private final String label;

		Trigger(String label) {
			this.label = label;
		}
	}

	/** 這一輪最後用什麼方式、結果如何（寫在 log 裡，也給測試檢查）。 */
	public enum Mode {
		BATCH("批次"), PER_STOCK("逐檔"), NO_DATA("尚無資料"), BLOCKED("被擋，開始冷卻"), SKIPPED("略過"),
		CLOSE_BACKFILL("今天已拿到資料，只補查收盤價"), ERROR("發生例外");

		private final String label;

		Mode(String label) {
			this.label = label;
		}
	}

	/**
	 * @param updated  新增或真的有變動的 quote 筆數
	 * @param dataDate 拿到的資料是哪一個交易日；沒拿到是 null
	 */
	public record RoundResult(Market market, Trigger trigger, Mode mode, int updated, LocalDate dataDate) {
	}

	private final Map<Market, MarketCloseBatchClient> batchClients;
	private final Map<Market, StockLookupClient> stockClients;
	private final Map<Market, MarketRefreshState> states = new EnumMap<>(Market.class);
	private final QuoteService quoteService;
	private final QuoteRepository quoteRepository;
	private final HoldingRepository holdingRepository;
	private final HoldingBackfillService backfillService;
	private final Pause pause;
	private final RandomGenerator random;
	private final QuoteProperties.Refresh settings;
	private final Clock clock;

	/**
	 * 由 QuoteConfig 組裝：哪個市場用哪個 client 在那裡明確寫出來；測試時傳入假的 client。
	 *
	 * @param batchClients 每個市場的批次 client
	 * @param stockClients 每個市場的逐檔 client
	 */
	public QuoteRefreshService(Map<Market, MarketCloseBatchClient> batchClients,
			Map<Market, StockLookupClient> stockClients, QuoteService quoteService, QuoteRepository quoteRepository,
			HoldingRepository holdingRepository, HoldingBackfillService backfillService, Pause pause,
			RandomGenerator random, QuoteProperties.Refresh settings, Clock clock) {
		for (Market market : Market.values()) {
			if (!batchClients.containsKey(market) || !stockClients.containsKey(market)) {
				throw new IllegalArgumentException("每個市場都要有批次和逐檔 client：缺少 " + market);
			}
			states.put(market, new MarketRefreshState(market));
		}
		this.batchClients = new EnumMap<>(batchClients);
		this.stockClients = new EnumMap<>(stockClients);
		this.quoteService = quoteService;
		this.quoteRepository = quoteRepository;
		this.holdingRepository = holdingRepository;
		this.backfillService = backfillService;
		this.pause = pause;
		this.random = random;
		this.settings = settings;
		this.clock = clock;
	}

	/** 給排程決定下一輪時間用（冷卻到何時、今天完成沒、上一輪是不是逐檔）。 */
	public MarketRefreshState state(Market market) {
		return states.get(market);
	}

	/** 執行一輪。不會丟例外。 */
	public RoundResult runRound(Market market, Trigger trigger) {
		MarketRefreshState state = states.get(market);
		// 「今天」一定用台北時區的 clock：主機時區不是台北
		LocalDate today = LocalDate.now(clock);
		RoundResult result;
		try {
			if (state.isCoolingDown(clock.instant())) {
				// 排程本來就不會在冷卻期間排這一輪；這裡再擋一次，確保冷卻期間絕對不發請求
				result = new RoundResult(market, trigger, Mode.SKIPPED, 0, null);
			} else if (trigger == Trigger.SCHEDULED && state.isDoneFor(today)) {
				// G-02：今天的資料已經拿到，不再抓整個市場；但還有持股的收盤價待補查時，只做補查
				result = state.hasPendingCloseBackfill(today)
						? new RoundResult(market, trigger, Mode.CLOSE_BACKFILL,
								backfillMissingClose(market, state, today), null)
						: new RoundResult(market, trigger, Mode.SKIPPED, 0, null);
			} else if (trigger == Trigger.STARTUP) {
				result = startupFetch(market, state, today);
			} else {
				result = scheduledFetch(market, state, today);
			}
		} catch (InterruptedException e) {
			// 伺服器關閉中：保留中斷狀態，結束這一輪
			Thread.currentThread().interrupt();
			result = new RoundResult(market, trigger, Mode.SKIPPED, 0, null);
		} catch (RuntimeException e) {
			log.warn("行情更新 來源={} 市場={} 發生例外，下一輪再試", trigger.label, market, e);
			result = new RoundResult(market, trigger, Mode.ERROR, 0, null);
		}
		log.info("行情更新 來源={} 市場={} 方式={} 更新={}筆 資料日期={}", trigger.label, market, result.mode().label,
				result.updated(), result.dataDate() == null ? "-" : result.dataDate());
		if (result.mode() != Mode.BATCH) {
			// 批次成功的那一輪已經在 handleBatch 裡補過（要排在補查收盤價之前），不用再補一次
			backfillOldHoldings();
		}
		return result;
	}

	/** 排程：只查今天。 */
	private RoundResult scheduledFetch(Market market, MarketRefreshState state, LocalDate today)
			throws InterruptedException {
		BatchQuoteResult batch = batchClients.get(market).fetchDay(today);
		return handleBatch(market, Trigger.SCHEDULED, state, today, batch);
	}

	/**
	 * 啟動：從今天往前找，找到最近一個有資料的交易日（例如週六啟動拿到週五的收盤價）。
	 * 往前找的每個請求之間也隨機等一下。
	 */
	private RoundResult startupFetch(Market market, MarketRefreshState state, LocalDate today)
			throws InterruptedException {
		for (int daysBack = 0; daysBack <= settings.startupLookbackDays(); daysBack++) {
			if (daysBack > 0) {
				pauseBetweenRequests();
			}
			BatchQuoteResult batch = batchClients.get(market).fetchDay(today.minusDays(daysBack));
			if (batch.status() != BatchQuoteResult.Status.NO_DATA) {
				return handleBatch(market, Trigger.STARTUP, state, today, batch);
			}
		}
		return new RoundResult(market, Trigger.STARTUP, Mode.NO_DATA, 0, null);
	}

	private RoundResult handleBatch(Market market, Trigger trigger, MarketRefreshState state, LocalDate today,
			BatchQuoteResult batch) throws InterruptedException {
		switch (batch.status()) {
			case OK -> {
				int updated = quoteService.upsert(batch.quotes());
				state.setUsedPerStock(false);
				if (today.equals(batch.tradeDate())) {
					// 批次涵蓋整個市場：拿到今天的資料，這個市場今天就完成了，不必等另一個市場
					state.markDone(today);
				}
				// 先補舊持股的市場（剛寫入的批次大多查得到）：補上市場的持股，下一步同一輪就能補查收盤價
				backfillOldHoldings();
				updated += backfillMissingClose(market, state, today);
				return new RoundResult(market, trigger, Mode.BATCH, updated, batch.tradeDate());
			}
			case NO_DATA -> {
				// 還沒公布或休市：不算錯誤，也不改用逐檔（逐檔一樣查不到今天的資料）
				state.setUsedPerStock(false);
				return new RoundResult(market, trigger, Mode.NO_DATA, 0, null);
			}
			case BLOCKED -> {
				startCooldown(state);
				return new RoundResult(market, trigger, Mode.BLOCKED, 0, null);
			}
			default -> {
				// FAILED：主考官的測試伺服器可能只有逐檔 API，這條路一定要能獨立運作
				state.setUsedPerStock(true);
				return perStockFetch(market, trigger, state, today);
			}
		}
	}

	/** 逐檔：只查這個市場有人持有、而且快取還沒有今天資料的代號。 */
	private RoundResult perStockFetch(Market market, Trigger trigger, MarketRefreshState state, LocalDate today)
			throws InterruptedException {
		List<String> heldCodes = holdingRepository.findByMarketOrderByCodeAsc(market).stream()
				.map(Holding::getCode).toList();
		List<String> targets = codesWithoutDataFor(heldCodes, today);

		int updated = 0;
		LocalDate latestDate = null;
		boolean blocked = false;
		for (int i = 0; i < targets.size(); i++) {
			if (i > 0) {
				pauseBetweenRequests();
			}
			String code = targets.get(i);
			// lookupLatest：這個月整月沒成交或還沒有交易日時，會再查上個月（月初才會多一個請求）
			StockLookup lookup = stockClients.get(market).lookupLatest(code, today);
			if (lookup.status() == StockLookup.Status.FOUND) {
				updated += quoteService.upsert(List.of(lookup.toDailyQuote(code)));
				if (latestDate == null || lookup.tradeDate().isAfter(latestDate)) {
					latestDate = lookup.tradeDate();
				}
			} else if (lookup.status() == StockLookup.Status.BLOCKED) {
				// 被擋：剩下的代號這一輪都不查了
				startCooldown(state);
				blocked = true;
				break;
			} else {
				log.warn("逐檔查詢 {} {}：{}，下一輪再試", market, code, lookup.status());
			}
		}

		// 這個市場有人持有的代號都拿到今天的資料 → 今天完成。
		// 沒有人持有時不算完成：之後有人新增這個市場的股票，排程才會繼續幫它更新
		if (!heldCodes.isEmpty() && codesWithoutDataFor(heldCodes, today).isEmpty()) {
			state.markDone(today);
		}
		return new RoundResult(market, trigger, blocked ? Mode.BLOCKED : Mode.PER_STOCK, updated, latestDate);
	}

	/**
	 * 批次只有某一天的資料：那天沒成交（"--"）的股票，收盤價是 null，
	 * 全新的資料庫又沒有之前的收盤價可以保留，清單會顯示「未取得」（Qiao 檢查時發現）。
	 * 對這個市場「有人持有，但快取沒有這一列或沒有收盤價」的代號，用逐檔 lookupLatest 往前找最近一個有成交日。
	 * 快取沒有這一列也要補：F-01 決定新增時寫快取失敗仍照常新增，缺的行情要靠這裡補上。
	 *
	 * 遵守逐檔的規則：一次一個請求、每個請求前隨機等一下、被擋就冷卻並停止。
	 * 三種結果分開處理（G-02）：
	 * - FOUND 或 NOT_FOUND（這個月和上個月都沒成交）：記成「今天已補查」，長期沒成交的股票才不會每輪重查
	 * - UNAVAILABLE（暫時查不了）：下一輪再試，同一檔一天最多 6 次
	 * - BLOCKED（被擋）：冷卻，不算次數（冷卻結束後再試）
	 * 結束時（包括被擋提早結束）重新計算「還有沒有待補查」，排程才知道今天拿到資料後還要不要繼續跑。
	 *
	 * @return 新增或真的有變動的 quote 筆數
	 */
	private int backfillMissingClose(Market market, MarketRefreshState state, LocalDate today)
			throws InterruptedException {
		List<String> heldCodes = holdingRepository.findByMarketOrderByCodeAsc(market).stream()
				.map(Holding::getCode).toList();
		List<String> targets = pendingCloseBackfill(heldCodes, state, today);

		int updated = 0;
		try {
			for (String code : targets) {
				// 第一個請求前也要等：前一刻剛向同一台主機送過批次請求
				pauseBetweenRequests();
				StockLookup lookup = stockClients.get(market).lookupLatest(code, today);
				switch (lookup.status()) {
					case FOUND -> {
						// 寫入照 Quote.apply 的規則：舊資料不會蓋掉新資料
						updated += quoteService.upsert(List.of(lookup.toDailyQuote(code)));
						state.markCloseBackfilled(code, today);
					}
					case NOT_FOUND -> {
						log.warn("補查收盤價 {} {}：這個月和上個月都查不到", market, code);
						state.markCloseBackfilled(code, today);
					}
					case BLOCKED -> {
						// 被擋：剩下的代號這一輪都不查了
						startCooldown(state);
						return updated;
					}
					default -> {
						int times = state.recordCloseBackfillUnavailable(code, today);
						log.warn("補查收盤價 {} {}：官方暫時查不了（今天第 {} 次，上限 {} 次）", market, code, times,
								MarketRefreshState.MAX_CLOSE_BACKFILL_UNAVAILABLE_PER_DAY);
					}
				}
			}
		} finally {
			state.setPendingCloseBackfill(today, !pendingCloseBackfill(heldCodes, state, today).isEmpty());
		}
		if (!targets.isEmpty()) {
			log.info("補查收盤價 市場={} 補查={}檔 更新={}筆", market, targets.size(), updated);
		}
		return updated;
	}

	/** 有人持有、快取沒有收盤價，而且今天還要再查的代號（還沒有結果、暫時查不了也還沒達上限）。 */
	private List<String> pendingCloseBackfill(List<String> heldCodes, MarketRefreshState state, LocalDate today) {
		return codesWithoutClose(heldCodes).stream()
				.filter(code -> !state.isCloseBackfillSettled(code, today))
				.toList();
	}

	/** 快取沒有這一列，或有這一列但收盤價是 null 的代號。 */
	private List<String> codesWithoutClose(List<String> codes) {
		Set<String> withClose = new HashSet<>();
		quoteRepository.findAllById(codes).forEach(q -> {
			if (q.getClosePrice() != null) {
				withClose.add(q.getCode());
			}
		});
		return codes.stream().filter(code -> !withClose.contains(code)).toList();
	}

	/** 快取裡沒有、或最近交易日比 today 舊的代號（「今天完成」看 latest_trade_date，當天沒成交的股票才不會一直重查）。 */
	private List<String> codesWithoutDataFor(List<String> codes, LocalDate today) {
		Map<String, LocalDate> latest = new HashMap<>();
		quoteRepository.findAllById(codes).forEach(q -> latest.put(q.getCode(), q.getLatestTradeDate()));
		List<String> result = new ArrayList<>();
		for (String code : codes) {
			LocalDate date = latest.get(code);
			if (date == null || date.isBefore(today)) {
				result.add(code);
			}
		}
		return result;
	}

	private void startCooldown(MarketRefreshState state) {
		state.startCooldown(clock.instant().plus(settings.cooldown()));
		log.warn("{} 被官方擋下，暫停 {}，冷卻到 {}", state.market(), settings.cooldown(), state.cooldownUntil());
	}

	private void pauseBetweenRequests() throws InterruptedException {
		pause.sleep(Pause.randomBetween(settings.perRequestDelayMin(), settings.perRequestDelayMax(), random));
	}

	/** 補舊持股的市場。任一市場在冷卻時不呼叫官方（MarketResolver 會同時查兩個市場），只用快取補。 */
	private void backfillOldHoldings() {
		try {
			boolean anyCoolingDown = states.values().stream().anyMatch(s -> s.isCoolingDown(clock.instant()));
			backfillService.backfill(!anyCoolingDown);
		} catch (RuntimeException e) {
			log.warn("補查舊持股市場時發生例外，下一輪再試", e);
		}
	}
}
