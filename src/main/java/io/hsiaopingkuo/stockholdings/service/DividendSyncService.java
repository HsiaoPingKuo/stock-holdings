package io.hsiaopingkuo.stockholdings.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.hsiaopingkuo.stockholdings.config.QuoteProperties;
import io.hsiaopingkuo.stockholdings.dto.CodeFirstTradeDate;
import io.hsiaopingkuo.stockholdings.entity.DividendDetailStatus;
import io.hsiaopingkuo.stockholdings.entity.DividendEvent;
import io.hsiaopingkuo.stockholdings.entity.DividendFetchRange;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.quote.DividendDetailClient;
import io.hsiaopingkuo.stockholdings.quote.DividendDetailResult;
import io.hsiaopingkuo.stockholdings.quote.DividendListClient;
import io.hsiaopingkuo.stockholdings.quote.DividendListResult;
import io.hsiaopingkuo.stockholdings.repository.DividendEventRepository;
import io.hsiaopingkuo.stockholdings.repository.DividendFetchRangeRepository;
import io.hsiaopingkuo.stockholdings.repository.TradeRepository;

/**
 * 除權息同步「一輪」（第 6 題設計決定 7、第 4b 階段）：抓列表、存事件、補上市明細、記錄已抓範圍。
 * 這一階段不排程，由程式呼叫；什麼時候跑、被擋後冷卻多久由 4c 的排程決定。
 *
 * 一個市場一輪的流程：
 * ① 從所有交易最早的那一年（最早 2021）到今年，挑出要抓的年份：抓完的過去年份跳過，今年每一輪都抓
 * ② 每一年查一次列表：有資料 → 寫入事件；沒資料 → 記成抓過；失敗 → 不記錄，下一輪再試；被擋 → 停止
 * ③ 只有上市：對 holding 表裡的代號、除權息日晚於那檔第一筆交易日、用列表算不出官方值的事件查明細
 *
 * 官方 API 一律在交易外呼叫（F-01）：這個類別不加 @Transactional，寫入交給 DividendEventWriter 的短交易。
 * 同一個市場的每兩個請求之間隨機等 3.2～5.8 秒（沿用行情的設定）。不會丟例外。
 * 不加 @Service：在 QuoteConfig 用 @Bean 建立，市場和 client 的對應寫在那裡。
 */
public class DividendSyncService {

	private static final Logger log = LoggerFactory.getLogger(DividendSyncService.class);

	/** 題目的交易從 2021 年開始，官方也查得到 2021-01 的資料。 */
	static final int FIRST_YEAR = 2021;

	private final Map<Market, DividendListClient> listClients;
	private final DividendDetailClient twseDetailClient;
	private final DividendEventWriter writer;
	private final DividendEventRepository eventRepository;
	private final DividendFetchRangeRepository rangeRepository;
	private final TradeRepository tradeRepository;
	private final Pause pause;
	private final RandomGenerator random;
	private final QuoteProperties.Refresh settings;
	private final Clock clock;

	/**
	 * @param listClients      每個市場的除權息列表 client
	 * @param twseDetailClient 上市除權息明細 client（上櫃不需要明細）
	 */
	public DividendSyncService(Map<Market, DividendListClient> listClients, DividendDetailClient twseDetailClient,
			DividendEventWriter writer, DividendEventRepository eventRepository,
			DividendFetchRangeRepository rangeRepository, TradeRepository tradeRepository, Pause pause,
			RandomGenerator random, QuoteProperties.Refresh settings, Clock clock) {
		for (Market market : Market.values()) {
			DividendListClient client = listClients.get(market);
			if (client == null || client.market() != market) {
				throw new IllegalArgumentException("每個市場都要有自己的除權息列表 client：" + market);
			}
		}
		this.listClients = new EnumMap<>(listClients);
		this.twseDetailClient = twseDetailClient;
		this.writer = writer;
		this.eventRepository = eventRepository;
		this.rangeRepository = rangeRepository;
		this.tradeRepository = tradeRepository;
		this.pause = pause;
		this.random = random;
		this.settings = settings;
		this.clock = clock;
	}

	/** 依序同步上市、上櫃；其中一個被擋不影響另一個。 */
	public List<DividendSyncResult> syncAll() {
		List<DividendSyncResult> results = new ArrayList<>();
		for (Market market : Market.values()) {
			results.add(syncMarket(market));
		}
		return results;
	}

	/** 同步一個市場一輪。不會丟例外。 */
	public DividendSyncResult syncMarket(Market market) {
		Round round = new Round(market);
		try {
			round.outcome = run(round);
		} catch (InterruptedException e) {
			// 伺服器關閉：保留中斷旗標，讓上層的執行緒也知道要停
			Thread.currentThread().interrupt();
			round.outcome = DividendSyncResult.Outcome.INTERRUPTED;
		} catch (RuntimeException e) {
			log.error("除權息同步 {}：發生例外，這一輪停止", market, e);
			round.outcome = DividendSyncResult.Outcome.ERROR;
		}
		DividendSyncResult result = round.toResult();
		log.info("除權息同步 {}：{}，抓到年份 {}，失敗年份 {}，新增 {} 筆、更新 {} 筆，明細查 {} 筆（查到 {}、查不到 {}、失敗 {}）",
				market, result.outcome(), result.fetchedYears(), result.failedYears(), result.eventsInserted(),
				result.eventsUpdated(), result.detailsQueried(), result.detailsFound(), result.detailsNotFound(),
				result.detailsFailed());
		return result;
	}

	private DividendSyncResult.Outcome run(Round round) throws InterruptedException {
		Optional<LocalDate> earliestTrade = tradeRepository.findEarliestTradeDate();
		if (earliestTrade.isEmpty()) {
			return DividendSyncResult.Outcome.NO_TRADES;
		}
		// 「今天」一定用台北時區的 clock：主機時區不是台北
		LocalDate today = LocalDate.now(clock);
		for (int year : yearsToFetch(round.market, earliestTrade.get(), today)) {
			if (!fetchListYear(round, year, today)) {
				return DividendSyncResult.Outcome.BLOCKED;
			}
		}
		if (round.market == Market.TWSE && !fetchDetails(round, today)) {
			return DividendSyncResult.Outcome.BLOCKED;
		}
		return DividendSyncResult.Outcome.COMPLETED;
	}

	/** 最早交易那一年（最早 2021）到今年；抓完的過去年份跳過，今年一律重抓。由舊到新。 */
	private List<Integer> yearsToFetch(Market market, LocalDate earliestTrade, LocalDate today) {
		Set<Integer> completedYears = rangeRepository.findByMarket(market).stream()
				.filter(DividendFetchRange::isCompleted)
				.map(DividendFetchRange::getFetchYear)
				.collect(Collectors.toSet());
		List<Integer> years = new ArrayList<>();
		for (int year = Math.max(FIRST_YEAR, earliestTrade.getYear()); year <= today.getYear(); year++) {
			if (year == today.getYear() || !completedYears.contains(year)) {
				years.add(year);
			}
		}
		return years;
	}

	/** @return false 代表被擋，這個市場這一輪要停止 */
	private boolean fetchListYear(Round round, int year, LocalDate today) throws InterruptedException {
		beforeRequest(round);
		// 用「開始抓」的時間判斷抓完了沒：12/31 深夜開始、跨過午夜才回來的不算抓完，比較保守
		LocalDateTime startedAt = LocalDateTime.now(clock);
		boolean completed = startedAt.getYear() > year;
		LocalDate end = year == today.getYear() ? today : LocalDate.of(year, 12, 31);
		DividendListResult result = listClients.get(round.market).fetchRange(LocalDate.of(year, 1, 1), end);

		switch (result.status()) {
			case BLOCKED -> {
				log.warn("除權息同步 {} {}：被官方擋下，這個市場這一輪停止", round.market, year);
				return false;
			}
			case FAILED -> round.failedYears.add(year);
			case NO_DATA -> {
				writer.markYearFetched(round.market, year, completed, LocalDateTime.now(clock));
				round.fetchedYears.add(year);
			}
			case OK -> {
				try {
					DividendEventWriter.ListWriteResult written = writer.saveListYear(round.market, year,
							result.rows(), completed, LocalDateTime.now(clock));
					round.eventsInserted += written.inserted();
					round.eventsUpdated += written.updated();
					round.fetchedYears.add(year);
				} catch (RuntimeException e) {
					// 寫入失敗整年回滾（沒有記成抓過），下一輪再試；其他年份照常
					log.warn("除權息同步 {} {}：寫入失敗，下一輪再試：{}", round.market, year, e.getMessage());
					round.failedYears.add(year);
				}
			}
		}
		return true;
	}

	/**
	 * 上市明細：holding 表裡的代號（含已賣光的）、除權息日晚於那檔第一筆交易日、
	 * 待查或「失敗而且今天還沒查過」的事件。
	 *
	 * @return false 代表被擋
	 */
	private boolean fetchDetails(Round round, LocalDate today) throws InterruptedException {
		Map<String, LocalDate> firstTradeByCode = tradeRepository.findFirstTradeDateByCode().stream()
				.collect(Collectors.toMap(CodeFirstTradeDate::code, CodeFirstTradeDate::firstTradeDate));
		if (firstTradeByCode.isEmpty()) {
			return true;
		}
		// 除權息日當天或之前買的才領得到；第一筆交易之前的事件不影響這檔，查了也用不到
		List<DividendEvent> candidates = eventRepository
				.findDetailCandidates(Market.TWSE, firstTradeByCode.keySet(), DividendDetailStatus.PENDING,
						DividendDetailStatus.FAILED, today.atStartOfDay())
				.stream()
				.filter(event -> event.getExDate().isAfter(firstTradeByCode.get(event.getCode())))
				.toList();

		for (DividendEvent event : candidates) {
			beforeRequest(round);
			round.detailsQueried++;
			DividendDetailResult result = twseDetailClient.fetchDetail(event.getDetailStockNo(), event.getDetailDate());
			if (result.status() == DividendDetailResult.Status.BLOCKED) {
				log.warn("除權息同步 上市明細 {}：被官方擋下，這一輪停止", event);
				return false;
			}
			DividendDetailStatus saved;
			try {
				saved = writer.saveDetailResult(event.getId(), result, LocalDateTime.now(clock));
			} catch (RuntimeException e) {
				// 沒存成功，狀態維持原樣，下一輪會再查
				log.warn("除權息同步 上市明細 {}：寫入失敗，下一輪再試：{}", event, e.getMessage());
				round.detailsFailed++;
				continue;
			}
			if (saved == DividendDetailStatus.FOUND) {
				round.detailsFound++;
			} else if (saved == DividendDetailStatus.NOT_FOUND) {
				round.detailsNotFound++;
			} else {
				round.detailsFailed++;
			}
		}
		return true;
	}

	/** 同一個市場的每兩個請求之間隨機等一下（非整數秒），第一個請求不用等。 */
	private void beforeRequest(Round round) throws InterruptedException {
		if (round.requestSent) {
			pause.sleep(Pause.randomBetween(settings.perRequestDelayMin(), settings.perRequestDelayMax(), random));
		}
		round.requestSent = true;
	}

	/** 一輪進行中累計的結果；只在這一輪的執行緒裡使用。 */
	private static final class Round {
		private final Market market;
		private DividendSyncResult.Outcome outcome;
		private boolean requestSent;
		private final List<Integer> fetchedYears = new ArrayList<>();
		private final List<Integer> failedYears = new ArrayList<>();
		private int eventsInserted;
		private int eventsUpdated;
		private int detailsQueried;
		private int detailsFound;
		private int detailsNotFound;
		private int detailsFailed;

		private Round(Market market) {
			this.market = market;
		}

		private DividendSyncResult toResult() {
			return new DividendSyncResult(market, outcome, fetchedYears, failedYears, eventsInserted, eventsUpdated,
					detailsQueried, detailsFound, detailsNotFound, detailsFailed);
		}
	}
}
