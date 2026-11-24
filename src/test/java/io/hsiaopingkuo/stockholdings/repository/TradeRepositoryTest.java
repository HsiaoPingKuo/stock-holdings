package io.hsiaopingkuo.stockholdings.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import io.hsiaopingkuo.stockholdings.calc.TradeSide;
import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.entity.Trade;
import jakarta.persistence.EntityManager;

/**
 * 驗證 V4 的 trade 表和 Trade Entity 對得上（ddl-auto=validate），以及查詢順序、刪除持股的連帶效果。
 */
@DataJpaTest
class TradeRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 14, 0);

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private EntityManager entityManager;

	private Holding saveHolding(String code) {
		return holdingRepository.saveAndFlush(new Holding(code, "測試", Market.TWSE, 1, new BigDecimal("100.00"), NOW));
	}

	@Test
	void 可以存取交易且各欄位對得上() {
		Holding holding = saveHolding("2330");
		Trade saved = tradeRepository.saveAndFlush(new Trade(holding.getId(), TradeSide.SELL,
				LocalDate.of(2026, 11, 24), 1500, new BigDecimal("1030.50"), true, NOW));
		// 清掉快取，下面才會真的從資料庫讀
		entityManager.clear();

		Trade found = tradeRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getHoldingId()).isEqualTo(holding.getId());
		assertThat(found.getSide()).isEqualTo(TradeSide.SELL);
		assertThat(found.getTradeDate()).isEqualTo(LocalDate.of(2026, 11, 24));
		assertThat(found.getShares()).isEqualTo(1500);
		assertThat(found.getPrice()).isEqualByComparingTo("1030.50");
		assertThat(found.isDateEstimated()).isTrue();
		// 新建的交易版本號從 0 開始，和 V4 轉入的資料一致
		assertThat(found.getVersion()).isZero();
		assertThat(found.getCreatedAt()).isEqualTo(NOW);
		assertThat(found.getUpdatedAt()).isEqualTo(NOW);
	}

	@Test
	void 依日期再依id排序且只回這檔持股的交易() {
		Holding tsmc = saveHolding("2330");
		Holding other = saveHolding("2317");
		Trade late = tradeRepository.save(trade(tsmc, TradeSide.BUY, LocalDate.of(2026, 11, 24)));
		// 同一天的買、賣先後由計算核心決定；資料庫這層只負責「日期、id」的順序
		Trade sameDayFirst = tradeRepository.save(trade(tsmc, TradeSide.SELL, LocalDate.of(2026, 11, 20)));
		Trade sameDaySecond = tradeRepository.save(trade(tsmc, TradeSide.BUY, LocalDate.of(2026, 11, 20)));
		Trade early = tradeRepository.save(trade(tsmc, TradeSide.BUY, LocalDate.of(2026, 11, 3)));
		tradeRepository.save(trade(other, TradeSide.BUY, LocalDate.of(2026, 11, 1)));
		tradeRepository.flush();
		entityManager.clear();

		assertThat(tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(tsmc.getId()))
				.extracting(Trade::getId, Trade::getTradeDate)
				.containsExactly(
						tuple(early.getId(), LocalDate.of(2026, 11, 3)),
						tuple(sameDayFirst.getId(), LocalDate.of(2026, 11, 20)),
						tuple(sameDaySecond.getId(), LocalDate.of(2026, 11, 20)),
						tuple(late.getId(), LocalDate.of(2026, 11, 24)));
	}

	@Test
	void 用現有的方式刪除持股時交易一起刪除() {
		Holding tsmc = saveHolding("2330");
		Holding other = saveHolding("2317");
		tradeRepository.saveAndFlush(trade(tsmc, TradeSide.BUY, LocalDate.of(2026, 11, 20)));
		tradeRepository.saveAndFlush(trade(other, TradeSide.BUY, LocalDate.of(2026, 11, 20)));
		// 交易不留在快取裡，模擬現有刪除流程（只讀持股、刪持股）
		entityManager.clear();

		// 和 HoldingService.delete 走同一條路：外鍵是 ON DELETE CASCADE，不會因為有交易而失敗
		holdingRepository.delete(holdingRepository.findById(tsmc.getId()).orElseThrow());
		holdingRepository.flush();
		entityManager.clear();

		assertThat(tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(tsmc.getId())).isEmpty();
		assertThat(tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(other.getId())).hasSize(1);
	}

	private static Trade trade(Holding holding, TradeSide side, LocalDate date) {
		return new Trade(holding.getId(), side, date, 1000, new BigDecimal("100.00"), false, NOW);
	}
}
