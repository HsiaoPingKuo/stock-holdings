package io.hsiaopingkuo.stockholdings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.hsiaopingkuo.stockholdings.calc.TradeSide;
import io.hsiaopingkuo.stockholdings.config.QuoteProperties;
import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.entity.Trade;
import io.hsiaopingkuo.stockholdings.exception.TradeOversellException;
import io.hsiaopingkuo.stockholdings.exception.VersionConflictException;
import io.hsiaopingkuo.stockholdings.quote.Deadline;
import io.hsiaopingkuo.stockholdings.quote.MarketResolver;
import io.hsiaopingkuo.stockholdings.quote.StockLookup;
import io.hsiaopingkuo.stockholdings.repository.HoldingRepository;
import io.hsiaopingkuo.stockholdings.repository.QuoteRepository;
import io.hsiaopingkuo.stockholdings.repository.TradeRepository;
import io.hsiaopingkuo.stockholdings.support.TestHoldings;

/** 2.0 交易的新增、修改、刪除（第 6 題第 5a 階段），搭配真的資料庫（H2 記憶體模式 + Flyway）。 */
@DataJpaTest
// 不寫入除權息事件，DatabaseDividendEventProvider 讀出來一律是空的
@Import({ TradeService.class, StockIdentityResolver.class, QuoteService.class, DatabaseDividendEventProvider.class,
		TradeServiceTest.FixedClockConfig.class })
// addTrade() 不開交易（F-01），測試本身也不包交易，才測得出它有沒有自己開；資料由「清空資料」負責清掉
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TradeServiceTest {

	// 台北 2026-11-24（星期二）14:00
	private static final Instant FIXED = Instant.parse("2026-11-24T06:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 14, 0);

	@TestConfiguration
	// TradeService 要讀 quote.request-budget
	@EnableConfigurationProperties(QuoteProperties.class)
	static class FixedClockConfig {
		@Bean
		Clock clock() {
			return Clock.fixed(FIXED, ZoneId.of("Asia/Taipei"));
		}
	}

	@Autowired
	private TradeService tradeService;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private QuoteRepository quoteRepository;

	// 不連網：全新代號一律判斷為上市
	@MockitoBean
	private MarketResolver marketResolver;

	@BeforeEach
	void 預設任何代號都判斷為上市() {
		given(marketResolver.resolve(anyString(), any(Deadline.class))).willAnswer(invocation -> StockLookup.found(
				Market.TWSE, "測試股", LocalDate.of(2026, 11, 23), new BigDecimal("10.00")));
	}

	@BeforeEach
	@AfterEach
	void 清空資料() {
		holdingRepository.deleteAll();
		quoteRepository.deleteAll();
	}

	// ===== 新增 =====

	@Test
	void 新代號買進_建立持股和一筆使用者填的買進() {
		Trade trade = tradeService.addTrade(" 2330 ", TradeSide.BUY, LocalDate.of(2026, 11, 20), 1500,
				new BigDecimal("1000.50"));

		Holding holding = holdingRepository.findById(trade.getHoldingId()).orElseThrow();
		assertThat(holding.getCode()).isEqualTo("2330");
		assertThat(holding.getMarket()).isEqualTo(Market.TWSE);
		assertThat(trade.getShares()).isEqualTo(1500);
		assertThat(trade.isDateEstimated()).isFalse();
	}

	@Test
	void 新代號賣出被擋_不建立持股也不查官方() {
		assertThatThrownBy(() -> tradeService.addTrade("2330", TradeSide.SELL, LocalDate.of(2026, 11, 20), 1000,
				new BigDecimal("1000")))
				.isInstanceOf(TradeOversellException.class)
				.hasMessage("2330 目前沒有持股，不能賣出");

		assertThat(holdingRepository.count()).isZero();
		verifyNoInteractions(marketResolver);
	}

	@Test
	void 已有持股_加買在同一筆持股下() {
		Holding holding = saveHolding("2330");
		saveTrade(holding, TradeSide.BUY, LocalDate.of(2026, 11, 18), 1000, false);

		Trade added = tradeService.addTrade("2330", TradeSide.BUY, LocalDate.of(2026, 11, 23), 500,
				new BigDecimal("1000"));

		assertThat(added.getHoldingId()).isEqualTo(holding.getId());
		assertThat(tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(holding.getId())).hasSize(2);
		assertThat(holdingRepository.count()).isEqualTo(1);
	}

	@Test
	void 股數0的持股_加買在原本的持股下() {
		Holding holding = saveHolding("2330");
		saveTrade(holding, TradeSide.BUY, LocalDate.of(2026, 11, 18), 1000, false);
		saveTrade(holding, TradeSide.SELL, LocalDate.of(2026, 11, 19), 1000, false);

		Trade added = tradeService.addTrade("2330", TradeSide.BUY, LocalDate.of(2026, 11, 23), 1000,
				new BigDecimal("1000"));

		assertThat(added.getHoldingId()).isEqualTo(holding.getId());
		assertThat(holdingRepository.count()).isEqualTo(1);
	}

	// ===== 日期與推估標記 =====

	@Test
	void 推估的週末日期_只改價格時不被擋_仍是推估() {
		// App 1.0 在星期六新增的持股：日期 = 建立日期（週六），標記推估
		Holding holding = TestHoldings.saveWithBuy(holdingRepository, tradeRepository, "2330", "台積電", Market.TWSE, 1,
				"1000.00", LocalDateTime.of(2026, 11, 21, 10, 0));
		Trade estimated = onlyTrade(holding);

		Trade updated = tradeService.updateTrade(estimated.getId(), estimated.getVersion(), LocalDate.of(2026, 11, 21),
				1000, new BigDecimal("990.00"));

		assertThat(updated.getPrice()).isEqualByComparingTo("990.00");
		assertThat(updated.isDateEstimated()).isTrue();
	}

	@Test
	void 改了日期_推估標記拿掉_版本號加1() {
		Holding holding = TestHoldings.saveWithBuy(holdingRepository, tradeRepository, "2330", "台積電", Market.TWSE, 1,
				"1000.00", LocalDateTime.of(2026, 11, 23, 10, 0));
		Trade estimated = onlyTrade(holding);

		Trade updated = tradeService.updateTrade(estimated.getId(), estimated.getVersion(), LocalDate.of(2026, 11, 20),
				1000, new BigDecimal("1000.00"));

		assertThat(updated.getTradeDate()).isEqualTo(LocalDate.of(2026, 11, 20));
		assertThat(updated.isDateEstimated()).isFalse();
		assertThat(updated.getVersion()).isEqualTo(estimated.getVersion() + 1);
		// 確認真的寫進資料庫，不只是記憶體裡的物件
		assertThat(onlyTrade(holding).isDateEstimated()).isFalse();
	}

	// ===== 超賣 =====

	@Test
	void 賣太多被擋_訊息說明哪一天賣多少_當時持有多少() {
		Holding holding = saveHolding("2330");
		saveTrade(holding, TradeSide.BUY, LocalDate.of(2026, 11, 18), 1000, false);

		assertThatThrownBy(() -> tradeService.addTrade("2330", TradeSide.SELL, LocalDate.of(2026, 11, 23), 1500,
				new BigDecimal("1000")))
				.isInstanceOf(TradeOversellException.class)
				.hasMessage("2026-11-23 的賣出 1,500 股不夠賣：當時只持有 1,000 股");

		assertThat(tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(holding.getId())).hasSize(1);
	}

	@Test
	void 刪掉以前的買進_造成後面的賣出超賣_被擋並指出那筆賣出() {
		Holding holding = saveHolding("2330");
		Trade firstBuy = saveTrade(holding, TradeSide.BUY, LocalDate.of(2026, 11, 18), 1000, false);
		saveTrade(holding, TradeSide.BUY, LocalDate.of(2026, 11, 19), 1000, false);
		Trade sell = saveTrade(holding, TradeSide.SELL, LocalDate.of(2026, 11, 20), 1500, false);

		assertThatThrownBy(() -> tradeService.deleteTrade(firstBuy.getId(), firstBuy.getVersion()))
				.isInstanceOfSatisfying(TradeOversellException.class, e -> {
					assertThat(e.getMessage()).isEqualTo("2026-11-20 的賣出 1,500 股不夠賣：當時只持有 1,000 股");
					assertThat(e.getOversoldTradeId()).isEqualTo(sell.getId());
				});

		assertThat(tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(holding.getId())).hasSize(3);
	}

	@Test
	void 補登比推估日期更早的賣出_訊息指出那筆推估的買進() {
		// 1.0 轉入的持股：實際更早買進，但日期被推估成 11-23
		Holding holding = TestHoldings.saveWithBuy(holdingRepository, tradeRepository, "2330", "台積電", Market.TWSE, 1,
				"1000.00", LocalDateTime.of(2026, 11, 23, 10, 0));
		Trade estimated = onlyTrade(holding);

		assertThatThrownBy(() -> tradeService.addTrade("2330", TradeSide.SELL, LocalDate.of(2026, 11, 20), 500,
				new BigDecimal("1000")))
				.isInstanceOfSatisfying(TradeOversellException.class, e -> {
					assertThat(e.getMessage()).isEqualTo("2026-11-20 的賣出 500 股不夠賣：當時只持有 0 股；"
							+ "2026-11-23 那筆 1,000 股的買進日期是推估的，實際買進日可能更早，請先把它改成實際的買進日期");
					assertThat(e.getEstimatedBuyTradeId()).isEqualTo(estimated.getId());
					// 超賣的是這次要新增、還沒有 id 的那一筆
					assertThat(e.getOversoldTradeId()).isNull();
				});
	}

	// ===== 版本號、刪除 =====

	@Test
	void 版本號不一致_修改和刪除都被擋() {
		Holding holding = saveHolding("2330");
		Trade trade = saveTrade(holding, TradeSide.BUY, LocalDate.of(2026, 11, 18), 1000, false);
		long staleVersion = trade.getVersion() + 1;

		assertThatThrownBy(() -> tradeService.updateTrade(trade.getId(), staleVersion, LocalDate.of(2026, 11, 18), 2000,
				new BigDecimal("1000")))
				.isInstanceOf(VersionConflictException.class)
				.hasMessage("這筆交易已被其他裝置修改，請重新整理");
		assertThatThrownBy(() -> tradeService.deleteTrade(trade.getId(), staleVersion))
				.isInstanceOf(VersionConflictException.class);

		assertThat(onlyTrade(holding).getShares()).isEqualTo(1000);
	}

	@Test
	void 刪掉最後一筆交易_持股一起刪() {
		Holding holding = saveHolding("2330");
		Trade trade = saveTrade(holding, TradeSide.BUY, LocalDate.of(2026, 11, 18), 1000, false);

		tradeService.deleteTrade(trade.getId(), trade.getVersion());

		assertThat(holdingRepository.findById(holding.getId())).isEmpty();
		assertThat(tradeRepository.count()).isZero();
	}

	// ===== 測試資料 =====

	private Holding saveHolding(String code) {
		return holdingRepository.saveAndFlush(new Holding(code, "測試股", Market.TWSE, NOW));
	}

	private Trade saveTrade(Holding holding, TradeSide side, LocalDate date, long shares, boolean estimated) {
		return tradeRepository.saveAndFlush(
				new Trade(holding.getId(), side, date, shares, new BigDecimal("1000.00"), estimated, NOW));
	}

	private Trade onlyTrade(Holding holding) {
		return tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(holding.getId()).get(0);
	}
}
