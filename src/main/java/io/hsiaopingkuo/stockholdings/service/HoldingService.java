package io.hsiaopingkuo.stockholdings.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.hsiaopingkuo.stockholdings.dto.HoldingListItem;
import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.exception.DuplicateCodeException;
import io.hsiaopingkuo.stockholdings.exception.HoldingNotFoundException;
import io.hsiaopingkuo.stockholdings.exception.MarketUnavailableException;
import io.hsiaopingkuo.stockholdings.exception.StockCodeNotFoundException;
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

	private final HoldingRepository holdingRepository;
	private final MarketResolver marketResolver;
	private final Clock clock;

	public HoldingService(HoldingRepository holdingRepository, MarketResolver marketResolver, Clock clock) {
		this.holdingRepository = holdingRepository;
		this.marketResolver = marketResolver;
		this.clock = clock;
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
	 * @throws StockCodeNotFoundException  上市、上櫃都查不到這個代號（第 3 題）
	 * @throws MarketUnavailableException  官方網站連不上，無法確認代號（第 3 題）
	 */
	@Transactional
	public Holding create(String code, int lots, BigDecimal buyPrice) {
		String normalizedCode = normalizeCode(code);

		// 第一道檢查：先查一次，大部分重複新增在這裡就擋下，訊息也比較好懂；
		// 放在連官方 API 之前，重複代號就不用白白等網路
		if (holdingRepository.existsByCode(normalizedCode)) {
			throw new DuplicateCodeException(normalizedCode);
		}

		// 第 3 題：由官方 API 判斷上市或上櫃；查不到或無法確認時直接丟例外，後面的儲存不會執行
		StockLookup lookup = marketResolver.resolve(normalizedCode);

		try {
			// saveAndFlush：立刻把 INSERT 送到資料庫，違反約束時在這裡就丟例外，才接得到
			return holdingRepository.saveAndFlush(
					new Holding(normalizedCode, lookup.name(), lookup.market(), lots, buyPrice, now()));
		} catch (DataIntegrityViolationException e) {
			// 最後防線：兩個請求同時新增同一代號時，上面的檢查都會通過，由資料庫的 UNIQUE 約束擋下
			if (isUniqueCodeViolation(e)) {
				throw new DuplicateCodeException(normalizedCode);
			}
			// 其他約束錯誤（理論上表單檢查已擋掉）不偽裝成重複，照原樣丟出
			throw e;
		}
	}

	/**
	 * 新增表單輸入代號時的即時提示：查這個代號的名稱與市場。
	 * 結果由 MarketResolver 記住 10 分鐘，使用者接著按下新增時不會再打一次官方。
	 * NOT_SUPPORTED：這裡完全不碰資料庫，不要為了等官方回應（最多 8 秒）白白佔住一條資料庫連線。
	 *
	 * @throws StockCodeNotFoundException 上市、上櫃都查不到
	 * @throws MarketUnavailableException 官方網站連不上，無法確認
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public StockLookup lookupStock(String code) {
		return marketResolver.resolve(normalizeCode(code));
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
