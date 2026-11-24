package io.hsiaopingkuo.stockholdings.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.hsiaopingkuo.stockholdings.calc.DividendEventInput;
import io.hsiaopingkuo.stockholdings.calc.HoldingReplayCalculator;
import io.hsiaopingkuo.stockholdings.calc.OversellException;
import io.hsiaopingkuo.stockholdings.calc.TradeInput;
import io.hsiaopingkuo.stockholdings.calc.TradeSide;
import io.hsiaopingkuo.stockholdings.calc.V1Compatibility;
import io.hsiaopingkuo.stockholdings.config.QuoteProperties;
import io.hsiaopingkuo.stockholdings.dto.HoldingListItem;
import io.hsiaopingkuo.stockholdings.dto.HoldingQuoteRow;
import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.entity.Quote;
import io.hsiaopingkuo.stockholdings.entity.Trade;
import io.hsiaopingkuo.stockholdings.exception.DuplicateCodeException;
import io.hsiaopingkuo.stockholdings.exception.HoldingBusyException;
import io.hsiaopingkuo.stockholdings.exception.HoldingDataException;
import io.hsiaopingkuo.stockholdings.exception.HoldingNotFoundException;
import io.hsiaopingkuo.stockholdings.exception.MarketAmbiguousException;
import io.hsiaopingkuo.stockholdings.exception.MarketUnavailableException;
import io.hsiaopingkuo.stockholdings.exception.StockCodeNotFoundException;
import io.hsiaopingkuo.stockholdings.exception.UpgradeRequiredException;
import io.hsiaopingkuo.stockholdings.quote.Deadline;
import io.hsiaopingkuo.stockholdings.quote.MarketResolver;
import io.hsiaopingkuo.stockholdings.quote.StockLookup;
import io.hsiaopingkuo.stockholdings.repository.HoldingRepository;
import io.hsiaopingkuo.stockholdings.repository.TradeRepository;
import io.hsiaopingkuo.stockholdings.service.StockIdentityResolver.StockIdentity;

/**
 * 持股的新增、修改、刪除、查詢。
 * 業務規則（代號正規化、同代號只能一筆）放在這一層，網頁和 App API 共用同一套規則。
 *
 * 第 6 題：持股的股數、成本一律由交易（trade）重播算出，不再讀寫 holding.lots、buy_price。
 * 這裡的新增、修改、刪除是「App 1.0 的方式」（網頁目前的畫面也一樣）：只處理單筆整張買進的持股，
 * 其他情況回 UPGRADE_REQUIRED（設計決定 2、3、4）。
 */
@Service
// 預設唯讀交易；會寫入的方法再個別標 @Transactional 蓋過去
@Transactional(readOnly = true)
public class HoldingService {

	// 對應 V1 migration 裡的約束名稱，用來辨認「是不是違反同代號只能一筆」
	private static final String UNIQUE_CODE_CONSTRAINT = "UK_HOLDING_CODE";

	private static final Logger log = LoggerFactory.getLogger(HoldingService.class);

	private final HoldingRepository holdingRepository;
	private final TradeRepository tradeRepository;
	private final DividendEventProvider dividendEventProvider;
	private final QuoteService quoteService;
	private final MarketResolver marketResolver;
	private final StockIdentityResolver stockIdentityResolver;
	private final Clock clock;
	// 新增持股的時間預算（quote.request-budget）
	private final Duration requestBudget;
	// create() 裡的短交易。用 TransactionTemplate 在程式裡明確劃出範圍：
	// 同一個類別裡呼叫自己另一個標 @Transactional 的方法不會經過 Spring 的代理，交易不會生效
	private final TransactionTemplate writeTransaction;

	public HoldingService(HoldingRepository holdingRepository, TradeRepository tradeRepository,
			DividendEventProvider dividendEventProvider, QuoteService quoteService, MarketResolver marketResolver,
			StockIdentityResolver stockIdentityResolver, Clock clock, QuoteProperties quoteProperties,
			PlatformTransactionManager transactionManager) {
		this.holdingRepository = holdingRepository;
		this.tradeRepository = tradeRepository;
		this.dividendEventProvider = dividendEventProvider;
		this.quoteService = quoteService;
		this.marketResolver = marketResolver;
		this.stockIdentityResolver = stockIdentityResolver;
		this.clock = clock;
		this.requestBudget = quoteProperties.requestBudget();
		this.writeTransaction = new TransactionTemplate(transactionManager);
	}

	// ===== 讀取 =====

	/**
	 * 清單頁：持股加上收盤價和交易重播的結果。只讀資料庫（行情快取），不呼叫官方。
	 * 不論幾檔持股都只查三次：持股＋行情（一個 JOIN）、全部交易、除權息事件，在記憶體裡組合。
	 * 股數 0（在 2.0 全部賣光）的持股不列出；資料異常的照樣列出，網頁要讓使用者看到（/api/v1 由 controller 濾掉）。
	 */
	public List<HoldingListItem> findAllForList() {
		List<HoldingQuoteRow> rows = holdingRepository.findAllForList();
		// 查詢已經依持股、日期、id 排好，groupingBy 會保留每一檔裡的順序
		Map<Long, List<TradeInput>> tradesByHolding = tradeRepository.findAllByOrderByHoldingIdAscTradeDateAscIdAsc()
				.stream()
				.collect(Collectors.groupingBy(Trade::getHoldingId,
						Collectors.mapping(Trade::toInput, Collectors.toList())));
		Map<String, List<DividendEventInput>> eventsByCode = dividendEventProvider
				.eventsByCode(rows.stream().map(HoldingQuoteRow::code).toList());

		return rows.stream()
				.map(row -> HoldingAssembler.assemble(row, tradesByHolding.getOrDefault(row.id(), List.of()),
						eventsByCode.getOrDefault(row.code(), List.of())))
				.filter(item -> item.dataError() || item.totalShares() > 0)
				.toList();
	}

	/**
	 * App API 的單筆持股、網頁修改頁：和清單同一套組裝，收盤價、盈虧和清單上的那一列一定相同。
	 *
	 * @throws HoldingNotFoundException 找不到，或股數是 0（在 2.0 全部賣光，比照清單當成不存在）
	 * @throws HoldingDataException     重播時超賣（資料被手動改過）
	 */
	public HoldingListItem findListItem(Long id) {
		HoldingQuoteRow row = holdingRepository.findListItemById(id).orElseThrow(() -> new HoldingNotFoundException(id));
		List<TradeInput> trades = tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(id).stream()
				.map(Trade::toInput)
				.toList();
		HoldingListItem item = HoldingAssembler.assemble(row, trades, eventsOf(row.code()));
		if (item.dataError()) {
			throw new HoldingDataException();
		}
		if (item.totalShares() == 0) {
			throw new HoldingNotFoundException(id);
		}
		return item;
	}

	public Holding findById(Long id) {
		return holdingRepository.findById(id).orElseThrow(() -> new HoldingNotFoundException(id));
	}

	// ===== 新增 =====

	/**
	 * 新增（決定 4）：
	 * - 代號沒有持股 → 新增持股＋一筆買進（日期 = 今天，標記推估）
	 * - 代號有持股、股數 > 0 → 重複代號
	 * - 代號有持股、股數 = 0 → 在原本的持股下加一筆買進，回傳原本的持股（id 不變）
	 *
	 * @throws DuplicateCodeException      代號已在持股中（股數大於 0）
	 * @throws HoldingBusyException        既有的持股正在被其他裝置修改，等不到鎖
	 * @throws HoldingDataException        既有的持股重播時超賣，無法判斷股數
	 * @throws StockCodeNotFoundException  行情快取沒有，上市、上櫃也都查不到這個代號（第 3 題）
	 * @throws MarketUnavailableException  行情快取沒有，官方網站又連不上，無法確認代號（第 3 題）
	 * @throws MarketAmbiguousException    行情快取沒有，兩個市場都查到且最近交易日相同，無法判斷（F-03）
	 */
	// F-01：整個方法不開交易。等官方回應最多要好幾秒，這段期間不能占著資料庫連線（更不能占著鎖），
	// 否則多人同時新增、官方又慢時，連線被占光，連清單頁都會被拖慢。
	// 注意：在這個方法裡，每一次存取資料庫都必須包在自己的短交易裡（writeTransaction、QuoteService）。
	// NOT_SUPPORTED 期間，不在交易中的資料庫存取（例如直接呼叫 repository 宣告的查詢方法，預設沒有交易）
	// 會拿一條連線、一直抓到這個方法結束才放，等官方時就又占著連線了
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public Holding create(String code, int lots, BigDecimal buyPrice) {
		// 時間預算從收到請求就開始算：後面排隊等名額、等官方都算在內，整個請求才不會超過題目要求的 10 秒
		Deadline deadline = Deadline.after(requestBudget);
		String normalizedCode = normalizeCode(code);
		long shares = lots * V1Compatibility.SHARES_PER_LOT;

		// 第一道檢查：代號已經有持股時，在一個短交易裡鎖住、重播再決定（重複，或歸零後加一筆買進）。
		// 放在連官方 API 之前：重複代號不用白白等網路；既有持股的名稱和市場也已經知道，不必查官方
		Holding existing = writeTransaction.execute(status -> addBuyIfSoldOut(normalizedCode, shares, buyPrice));
		if (existing != null) {
			return existing;
		}

		// 第 3 題：先查行情快取，沒有才問官方（這時沒有任何交易、沒有任何鎖）；
		// 查不到或無法確認時直接丟例外，後面的儲存不會執行
		StockIdentity identity = stockIdentityResolver.resolve(normalizedCode, deadline);

		return insertHoldingWithBuy(new Holding(normalizedCode, identity.name(), identity.market(), now()), shares,
				buyPrice);
	}

	/**
	 * 代號已有持股時的處理（在 writeTransaction 裡執行）：鎖住那一列、重播。
	 * 鎖住之後才判斷股數：兩個請求同時對歸零的持股新增時，第二個會看到第一個剛加的買進，變成重複代號。
	 *
	 * @return 加了一筆買進的既有持股；這個代號還沒有持股時回 null（交給後面的全新新增）
	 */
	private Holding addBuyIfSoldOut(String code, long shares, BigDecimal buyPrice) {
		Optional<Holding> found = lock(() -> holdingRepository.findByCodeForUpdate(code));
		if (found.isEmpty()) {
			return null;
		}
		LockedHolding locked = replayLocked(found.get());
		if (locked.shares() > 0) {
			throw new DuplicateCodeException(code);
		}
		tradeRepository.save(estimatedBuy(locked.holding().getId(), shares, buyPrice));
		return locked.holding();
	}

	/** 全新代號：持股和第一筆買進在同一個短交易裡寫入，不會出現「有持股、沒有交易」的半套資料。 */
	private Holding insertHoldingWithBuy(Holding holding, long shares, BigDecimal buyPrice) {
		try {
			// saveAndFlush：立刻把 INSERT 送到資料庫，違反約束時在這裡就丟例外，才接得到
			return writeTransaction.execute(status -> {
				Holding saved = holdingRepository.saveAndFlush(holding);
				tradeRepository.save(estimatedBuy(saved.getId(), shares, buyPrice));
				return saved;
			});
		} catch (DataIntegrityViolationException e) {
			// 最後防線：兩個請求同時新增同一個全新代號時，沒有那一列可以鎖，上面的檢查都會通過，
			// 由資料庫的 UNIQUE 約束擋下（設計決定 6-B）
			if (isUniqueCodeViolation(e)) {
				throw new DuplicateCodeException(holding.getCode());
			}
			// 其他約束錯誤（理論上表單檢查已擋掉）不偽裝成重複，照原樣丟出
			throw e;
		}
	}

	/**
	 * App 1.0 和目前的網頁新增都沒有買進日期：用今天（台北時間），並標記為推估（決定 4），
	 * 之後使用者在 2.0 改過日期才拿掉標記。
	 */
	private Trade estimatedBuy(Long holdingId, long shares, BigDecimal buyPrice) {
		return new Trade(holdingId, TradeSide.BUY, LocalDate.now(clock), shares, buyPrice, true, now());
	}

	/**
	 * 新增表單輸入代號時的即時提示：查這個代號的名稱與市場。
	 * 先查行情快取，有就直接回傳，不打官方；沒有才問官方，結果由 MarketResolver 記住 10 分鐘。
	 * NOT_SUPPORTED：不要為了等官方回應（最多 quote.request-budget，預設 9 秒）白白佔住一條資料庫連線；
	 * 查快取那一下由 QuoteService 自己開一個很短的唯讀交易。
	 *
	 * @throws StockCodeNotFoundException 快取沒有，上市、上櫃也都查不到
	 * @throws MarketUnavailableException 快取沒有，官方網站又連不上，無法確認
	 * @throws MarketAmbiguousException   快取沒有，兩個市場都查到且最近交易日相同，無法判斷
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public StockLookup lookupStock(String code) {
		String normalizedCode = normalizeCode(code);
		Optional<Quote> cached = quoteService.findByCode(normalizedCode);
		if (cached.isPresent()) {
			Quote quote = cached.get();
			return StockLookup.found(quote.getMarket(), quote.getName(), quote.getLatestTradeDate(),
					quote.getClosePrice(), quote.getCloseDate());
		}
		return marketResolver.resolve(normalizedCode);
	}

	// ===== 修改、刪除（App 1.0 的方式） =====

	/**
	 * App 1.0 修改（決定 3）：只改那一筆買進的股數（張數 × 1000）和價格，日期和推估標記不變。
	 *
	 * @throws HoldingNotFoundException 找不到，或股數是 0
	 * @throws UpgradeRequiredException 有多筆交易、零股或配過股，不能用 1.0 的方式修改
	 * @throws HoldingBusyException     別的裝置正在修改同一檔，等不到鎖
	 * @throws HoldingDataException     重播時超賣（資料被手動改過）
	 */
	@Transactional
	public Holding update(Long id, int lots, BigDecimal buyPrice) {
		LockedHolding locked = lockForV1Write(id);
		// 不用呼叫 save：交易結束時 JPA 會自動把改過的欄位寫回資料庫（dirty checking）
		locked.trades().get(0).changeSharesAndPrice(lots * V1Compatibility.SHARES_PER_LOT, buyPrice, now());
		return locked.holding();
	}

	/**
	 * App 1.0 刪除（決定 3）：刪除整筆持股，它的交易由外鍵的 ON DELETE CASCADE 一起刪掉。
	 * 多筆交易的持股也擋：刪了會清掉 2.0 的交易歷史，而 1.0 的使用者看不到這些交易。
	 *
	 * @throws HoldingNotFoundException 找不到，或股數是 0
	 * @throws UpgradeRequiredException 有多筆交易、零股或配過股
	 * @throws HoldingBusyException     別的裝置正在修改同一檔，等不到鎖
	 * @throws HoldingDataException     重播時超賣（資料被手動改過）
	 */
	@Transactional
	public void delete(Long id) {
		LockedHolding locked = lockForV1Write(id);
		holdingRepository.delete(locked.holding());
	}

	/**
	 * 修改、刪除共用：鎖住持股 → 重播 → 檢查「能不能用 1.0 的方式改」。
	 * 一定要先鎖再重播，才不會「判斷完、寫入前」被別的裝置改掉（設計決定 6-B）。
	 * 呼叫端必須在交易裡，鎖到交易結束才放開。
	 */
	private LockedHolding lockForV1Write(Long id) {
		Holding holding = lock(() -> holdingRepository.findByIdForUpdate(id))
				.orElseThrow(() -> new HoldingNotFoundException(id));
		LockedHolding locked = replayLocked(holding);
		// 在 2.0 全部賣光的持股，App 1.0 看不到，比照清單當成不存在
		if (locked.shares() == 0) {
			throw new HoldingNotFoundException(id);
		}
		if (!V1Compatibility.isV1Editable(locked.inputs(), locked.shares())) {
			throw new UpgradeRequiredException();
		}
		return locked;
	}

	/**
	 * 執行 SELECT … FOR UPDATE；等不到鎖時（H2 預設等 2 秒）資料庫丟逾時，轉成「正在被其他裝置修改」，不能變成 500。
	 */
	// 套件內共用：TradeService 鎖持股時用同一套轉換，等不到鎖的訊息才會一致
	static Optional<Holding> lock(Supplier<Optional<Holding>> query) {
		try {
			return query.get();
		} catch (PessimisticLockingFailureException e) {
			throw new HoldingBusyException(e);
		}
	}

	/** 已鎖住的持股，重播這一檔的交易和除權息，算出目前的股數。 */
	private LockedHolding replayLocked(Holding holding) {
		List<Trade> trades = tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(holding.getId());
		List<TradeInput> inputs = trades.stream().map(Trade::toInput).toList();
		try {
			// 只需要股數，不需要收盤價
			long shares = HoldingReplayCalculator.replay(holding.getCode(), inputs, eventsOf(holding.getCode()), null)
					.totalShares();
			return new LockedHolding(holding, trades, inputs, shares);
		} catch (OversellException e) {
			log.warn("持股 {}（id={}）重播交易失敗，資料可能被手動修改，拒絕這次寫入：{}", holding.getCode(), holding.getId(),
					e.getMessage());
			throw new HoldingDataException();
		}
	}

	/**
	 * 鎖住後的狀態。
	 *
	 * @param trades 交易 Entity（修改時直接改它）
	 * @param inputs 同樣的交易，轉成計算核心用的 record
	 * @param shares 重播後的目前股數（含配股）
	 */
	private record LockedHolding(Holding holding, List<Trade> trades, List<TradeInput> inputs, long shares) {
	}

	private List<DividendEventInput> eventsOf(String code) {
		return dividendEventProvider.eventsByCode(List.of(code)).getOrDefault(code, List.of());
	}

	/** 去掉前後空白並轉大寫，讓「 00631l」和「00631L」被視為同一個代號。 */
	public static String normalizeCode(String code) {
		// Locale.ROOT：避免在特殊語系（例如土耳其文）下大小寫轉換結果不同
		return code.trim().toUpperCase(Locale.ROOT);
	}

	private LocalDateTime now() {
		// 一定要傳 clock（台北時區），不能用沒參數的 now()
		return LocalDateTime.now(clock);
	}

	// 套件內共用：TradeService 新增全新代號時也要辨認同一個約束
	static boolean isUniqueCodeViolation(DataIntegrityViolationException e) {
		String message = e.getMostSpecificCause().getMessage();
		return message != null && message.toUpperCase(Locale.ROOT).contains(UNIQUE_CODE_CONSTRAINT);
	}
}
