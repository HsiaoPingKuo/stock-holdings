package io.hsiaopingkuo.stockholdings.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import io.hsiaopingkuo.stockholdings.dto.HoldingListItem;
import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.entity.Quote;
import io.hsiaopingkuo.stockholdings.quote.DailyQuote;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

/**
 * 驗證 V1、V2 migration 建出來的表：欄位對得上 Entity，而且資料庫本身的約束有作用。
 * 這裡刻意繞過表單和 service，直接寫資料庫，確認「最後一道防線」真的存在。
 */
@DataJpaTest
class HoldingRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 14, 0);

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private EntityManager entityManager;

	/** 測試只在意某些欄位時，名稱和市場用固定值。 */
	private static Holding holding(String code, int lots, BigDecimal buyPrice) {
		return new Holding(code, "測試", Market.TWSE, lots, buyPrice, NOW);
	}

	@Test
	void 可以存取持股且代號開頭的0不會掉() {
		Holding saved = holdingRepository.saveAndFlush(
				new Holding("0050", "元大台灣50", Market.TWSE, 2, new BigDecimal("150.00"), NOW));
		// 清掉 JPA 的快取，下面的 findById 才會真的從資料庫讀，確認欄位對應正確
		entityManager.clear();

		Holding found = holdingRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getCode()).isEqualTo("0050");
		assertThat(found.getName()).isEqualTo("元大台灣50");
		assertThat(found.getMarket()).isEqualTo(Market.TWSE);
		assertThat(found.getLots()).isEqualTo(2);
		assertThat(found.getBuyPrice()).isEqualByComparingTo("150.00");
		assertThat(holdingRepository.existsByCode("0050")).isTrue();
	}

	@Test
	void 名稱和市場允許NULL_第2題的舊資料升級後仍可讀取() {
		Holding saved = holdingRepository.saveAndFlush(new Holding("2330", null, null, 1, new BigDecimal("1000.00"), NOW));
		entityManager.clear();

		Holding found = holdingRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getName()).isNull();
		assertThat(found.getMarket()).isNull();
	}

	@Test
	void 資料庫的CHECK約束擋下TWSE和TPEX以外的市場() {
		// Entity 用 enum 寫不出錯的值，所以直接下 SQL，確認資料庫這道防線存在
		assertThatThrownBy(() -> entityManager.createNativeQuery(
				"INSERT INTO holding (code, market, lots, buy_price, created_at, updated_at) "
						+ "VALUES ('2330', 'NYSE', 1, 1000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")
				.executeUpdate())
				.isInstanceOf(PersistenceException.class)
				.hasStackTraceContaining("CK_HOLDING_MARKET");
	}

	@Test
	void 資料庫的UNIQUE約束擋下重複代號_錯誤訊息含約束名稱() {
		holdingRepository.saveAndFlush(holding("2330", 1, new BigDecimal("1000.00")));

		// HoldingService 靠錯誤訊息裡的約束名稱判斷是不是重複代號，這裡確認 H2 真的會帶出這個名稱
		assertThatThrownBy(() -> holdingRepository.saveAndFlush(holding("2330", 3, new BigDecimal("900.00"))))
				.isInstanceOf(DataIntegrityViolationException.class)
				.satisfies(e -> assertThat(((DataIntegrityViolationException) e).getMostSpecificCause().getMessage())
						.containsIgnoringCase("UK_HOLDING_CODE"));
	}

	@Test
	void 資料庫的CHECK約束擋下超出範圍的張數() {
		assertThatThrownBy(() -> holdingRepository.saveAndFlush(holding("2330", 1000, new BigDecimal("10.00"))))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 資料庫的CHECK約束擋下不大於0的買進價() {
		assertThatThrownBy(() -> holdingRepository.saveAndFlush(holding("2330", 1, BigDecimal.ZERO)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 清單依代號排序() {
		holdingRepository.saveAndFlush(holding("6488", 1, new BigDecimal("460.50")));
		holdingRepository.saveAndFlush(holding("0050", 1, new BigDecimal("150.00")));
		holdingRepository.saveAndFlush(holding("2330", 1, new BigDecimal("1000.00")));

		assertThat(holdingRepository.findAllByOrderByCodeAsc())
				.extracting(Holding::getCode)
				.containsExactly("0050", "2330", "6488");
	}

	// ===== 第 3 題：清單 = 持股 LEFT JOIN 行情快取 =====

	@Autowired
	private QuoteRepository quoteRepository;

	@Test
	void 清單查詢_帶出收盤價與兩個日期_沒有行情的持股也列出來_依代號排序() {
		LocalDate nov23 = LocalDate.of(2026, 11, 23);
		LocalDate nov24 = LocalDate.of(2026, 11, 24);
		holdingRepository.saveAndFlush(holding("6488", 1, new BigDecimal("450.00")));
		holdingRepository.saveAndFlush(holding("2330", 1, new BigDecimal("1000.00")));
		// 6488：11/24 無成交，最近一次成交是 11/23；2330 沒有行情
		Quote quote = new Quote(DailyQuote.ofDay("6488", "環球晶", Market.TPEX, nov23, new BigDecimal("460.50")), NOW);
		quote.apply(DailyQuote.ofDay("6488", "環球晶", Market.TPEX, nov24, null), NOW);
		quoteRepository.saveAndFlush(quote);
		entityManager.clear();

		List<HoldingListItem> rows = holdingRepository.findAllForList();

		assertThat(rows).extracting(HoldingListItem::code).containsExactly("2330", "6488");
		HoldingListItem noQuote = rows.get(0);
		assertThat(noQuote.closePrice()).isNull();
		assertThat(noQuote.hasClosePrice()).isFalse();
		HoldingListItem gw = rows.get(1);
		assertThat(gw.closePrice()).isEqualByComparingTo("460.50");
		assertThat(gw.closeDate()).isEqualTo(nov23);
		assertThat(gw.latestTradeDate()).isEqualTo(nov24);
		assertThat(gw.showsEarlierClose()).isTrue();
	}

	@Test
	void 清單查詢_持股沒有名稱時用行情快取的名稱() {
		holdingRepository.saveAndFlush(new Holding("2330", null, null, 1, new BigDecimal("1000.00"), NOW));
		quoteRepository.saveAndFlush(new Quote(
				DailyQuote.ofDay("2330", "台積電", Market.TWSE, LocalDate.of(2026, 11, 23), new BigDecimal("2030.00")), NOW));
		entityManager.clear();

		assertThat(holdingRepository.findAllForList().get(0).name()).isEqualTo("台積電");
	}
}
