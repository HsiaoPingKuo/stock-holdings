package io.hsiaopingkuo.stockholdings.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.hsiaopingkuo.stockholdings.config.QuoteProperties;
import io.hsiaopingkuo.stockholdings.dto.HoldingListItem;
import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.entity.Quote;
import io.hsiaopingkuo.stockholdings.exception.DuplicateCodeException;
import io.hsiaopingkuo.stockholdings.exception.HoldingNotFoundException;
import io.hsiaopingkuo.stockholdings.exception.MarketAmbiguousException;
import io.hsiaopingkuo.stockholdings.exception.MarketUnavailableException;
import io.hsiaopingkuo.stockholdings.exception.StockCodeNotFoundException;
import io.hsiaopingkuo.stockholdings.quote.Deadline;
import io.hsiaopingkuo.stockholdings.quote.MarketResolver;
import io.hsiaopingkuo.stockholdings.quote.StockLookup;
import io.hsiaopingkuo.stockholdings.repository.HoldingRepository;

/**
 * 持股的新增、修改、刪除、查詢。
 * 業務規則（代號正規化、同代號只能一筆）放在這一層，之後網頁和 App API 共用同一套規則。
 */
@Service
// 預設唯讀交易；會寫入的方法再個別標 @Transactional 蓋過去
@Transactional(readOnly = true)
public class HoldingService {

	// 對應 V1 migration 裡的約束名稱，用來辨認「是不是違反同代號只能一筆」
	private static final String UNIQUE_CODE_CONSTRAINT = "UK_HOLDING_CODE";

	private static final Logger log = LoggerFactory.getLogger(HoldingService.class);

	private final HoldingRepository holdingRepository;
	private final QuoteService quoteService;
	private final MarketResolver marketResolver;
	private final Clock clock;
	// 新增持股的時間預算（quote.request-budget）
	private final Duration requestBudget;
	// create() 裡的短交易：查重複用唯讀的，寫入持股用一般的。用 TransactionTemplate 在程式裡明確劃出範圍：
	// 同一個類別裡呼叫自己另一個標 @Transactional 的方法不會經過 Spring 的代理，交易不會生效
	private final TransactionTemplate readTransaction;
	private final TransactionTemplate writeTransaction;

	public HoldingService(HoldingRepository holdingRepository, QuoteService quoteService,
			MarketResolver marketResolver, Clock clock, QuoteProperties quoteProperties,
			PlatformTransactionManager transactionManager) {
		this.holdingRepository = holdingRepository;
		this.quoteService = quoteService;
		this.marketResolver = marketResolver;
		this.clock = clock;
		this.requestBudget = quoteProperties.requestBudget();
		this.readTransaction = new TransactionTemplate(transactionManager);
		this.readTransaction.setReadOnly(true);
		this.writeTransaction = new TransactionTemplate(transactionManager);
	}

	/** 清單頁：持股加上收盤價。只讀資料庫（行情快取），不呼叫官方，打開清單才不會每次都打官方。 */
	public List<HoldingListItem> findAllForList() {
		return holdingRepository.findAllForList();
	}

	public Holding findById(Long id) {
		return holdingRepository.findById(id).orElseThrow(() -> new HoldingNotFoundException(id));
	}

	/**
	 * @throws DuplicateCodeException      代號已在持股中
	 * @throws StockCodeNotFoundException  行情快取沒有，上市、上櫃也都查不到這個代號（第 3 題）
	 * @throws MarketUnavailableException  行情快取沒有，官方網站又連不上，無法確認代號（第 3 題）
	 * @throws MarketAmbiguousException    行情快取沒有，兩個市場都查到且最近交易日相同，無法判斷（F-03）
	 */
	// F-01：整個方法不開交易。等官方回應最多要好幾秒，這段期間不能占著資料庫連線，
	// 否則多人同時新增、官方又慢時，連線被占光，連清單頁都會被拖慢。
	// 注意：在這個方法裡，每一次存取資料庫都必須包在自己的短交易裡（readTransaction、QuoteService、writeTransaction）。
	// NOT_SUPPORTED 期間，不在交易中的資料庫存取（例如直接呼叫 existsByCode，Spring Data 自己宣告的查詢方法預設沒有交易）
	// 會拿一條連線、一直抓到這個方法結束才放，等官方時就又占著連線了
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public Holding create(String code, int lots, BigDecimal buyPrice) {
		// 時間預算從收到請求就開始算：後面排隊等名額、等官方都算在內，整個請求才不會超過題目要求的 10 秒
		Deadline deadline = Deadline.after(requestBudget);
		String normalizedCode = normalizeCode(code);

		// 第一道檢查：先查一次，大部分重複新增在這裡就擋下，訊息也比較好懂；
		// 放在連官方 API 之前，重複代號就不用白白等網路
		Boolean exists = readTransaction.execute(status -> holdingRepository.existsByCode(normalizedCode));
		if (Boolean.TRUE.equals(exists)) {
			throw new DuplicateCodeException(normalizedCode);
		}

		// 第 3 題：先查行情快取。排程每天會寫入全市場的行情，大部分代號在這裡就知道市場和名稱，不必打官方；
		// 快取沒有收盤價時，才只對那個市場補查收盤價
		Market market;
		String name;
		Optional<Quote> cached = quoteService.findByCode(normalizedCode);
		if (cached.isPresent()) {
			market = cached.get().getMarket();
			name = cached.get().getName();
			if (cached.get().getClosePrice() == null) {
				backfillCloseQuietly(normalizedCode, market, deadline);
			}
		} else {
			// 快取沒有才問官方（這時沒有任何交易）；查不到或無法確認時直接丟例外，後面的儲存不會執行
			StockLookup lookup = marketResolver.resolve(normalizedCode, deadline);
			saveQuoteQuietly(normalizedCode, lookup);
			market = lookup.market();
			name = lookup.name();
		}

		return insertHolding(new Holding(normalizedCode, name, market, lots, buyPrice, now()));
	}

	/**
	 * 快取有這一列、但沒有收盤價（例如批次那天剛好沒成交，資料庫又是全新的，巧馨檢查時發現）：
	 * 市場已經知道，只查那個市場的逐檔資料，往前找最近一個有成交日的收盤價。
	 * 時間預算和同時查詢上限照 F-01（由 MarketResolver 負責）。
	 * 補查失敗、逾時或被擋都不影響新增：持股照常存，收盤價留給背景排程補。
	 */
	private void backfillCloseQuietly(String code, Market market, Deadline deadline) {
		StockLookup lookup = marketResolver.lookupLatestInMarket(code, market, deadline);
		if (lookup.status() == StockLookup.Status.FOUND) {
			saveQuoteQuietly(code, lookup);
		} else {
			log.warn("新增 {} 時補查收盤價沒有結果（{}），持股照常新增，收盤價由背景排程補上", code, lookup.status());
		}
	}

	/**
	 * 官方已經回了最近交易日和收盤價，順手寫進快取：新增後清單馬上看得到價格。
	 * 用 QuoteService 自己的短交易，和寫入持股分開：
	 * 行情是官方公開資料，不論持股有沒有存成功都是正確的；反過來，快取寫入失敗也不該讓使用者的新增失敗，
	 * 缺的行情由背景排程補上。
	 */
	private void saveQuoteQuietly(String code, StockLookup lookup) {
		try {
			quoteService.upsert(List.of(lookup.toDailyQuote(code)));
		} catch (RuntimeException e) {
			// 最常見的原因：別人同時新增同一代號，搶先寫進了同一列。只記訊息，不印整段 stack trace
			log.warn("新增 {} 時寫入行情快取失敗，持股照常新增，行情由背景排程補上：{}", code, e.toString());
		}
	}

	private Holding insertHolding(Holding holding) {
		try {
			// 自己一個很短的交易；saveAndFlush：立刻把 INSERT 送到資料庫，違反約束時在這裡就丟例外，才接得到
			return writeTransaction.execute(status -> holdingRepository.saveAndFlush(holding));
		} catch (DataIntegrityViolationException e) {
			// 最後防線：兩個請求同時新增同一代號時，上面的檢查都會通過，由資料庫的 UNIQUE 約束擋下
			if (isUniqueCodeViolation(e)) {
				throw new DuplicateCodeException(holding.getCode());
			}
			// 其他約束錯誤（理論上表單檢查已擋掉）不偽裝成重複，照原樣丟出
			throw e;
		}
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

	@Transactional
	public Holding update(Long id, int lots, BigDecimal buyPrice) {
		Holding holding = findById(id);
		// 不用呼叫 save：交易結束時 JPA 會自動把改過的欄位寫回資料庫（dirty checking）
		holding.update(lots, buyPrice, now());
		return holding;
	}

	@Transactional
	public void delete(Long id) {
		Holding holding = findById(id);
		holdingRepository.delete(holding);
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

	private static boolean isUniqueCodeViolation(DataIntegrityViolationException e) {
		String message = e.getMostSpecificCause().getMessage();
		return message != null && message.toUpperCase(Locale.ROOT).contains(UNIQUE_CODE_CONSTRAINT);
	}
}
