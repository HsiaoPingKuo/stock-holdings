package io.hsiaopingkuo.stockholdings.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.hsiaopingkuo.stockholdings.calc.DividendEventInput;
import io.hsiaopingkuo.stockholdings.calc.DividendKind;
import io.hsiaopingkuo.stockholdings.calc.DividendSource;
import io.hsiaopingkuo.stockholdings.calc.TradeSide;
import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.entity.Quote;
import io.hsiaopingkuo.stockholdings.entity.Trade;
import io.hsiaopingkuo.stockholdings.quote.DailyQuote;
import io.hsiaopingkuo.stockholdings.quote.MarketResolver;
import io.hsiaopingkuo.stockholdings.quote.TpexOtcClient;
import io.hsiaopingkuo.stockholdings.quote.TpexTradingStockClient;
import io.hsiaopingkuo.stockholdings.quote.TwseMiIndexClient;
import io.hsiaopingkuo.stockholdings.quote.TwseStockDayClient;
import io.hsiaopingkuo.stockholdings.repository.HoldingRepository;
import io.hsiaopingkuo.stockholdings.repository.QuoteRepository;
import io.hsiaopingkuo.stockholdings.repository.TradeRepository;
import io.hsiaopingkuo.stockholdings.service.DividendEventProvider;

/**
 * 第 6 題 /api/v2 的讀取：清單（含已賣光、沒有收盤價）、單筆明細、總計、資料異常。
 * 真的資料庫和 JSON 設定；官方 client、除權息提供者換成假的，測試不會連網。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:api-v2-read;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ApiV2ReadTest {

	private static final Instant FIXED = Instant.parse("2026-11-25T06:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 14, 0);
	private static final LocalDate NOV_02 = LocalDate.of(2026, 11, 2);
	private static final LocalDate NOV_10 = LocalDate.of(2026, 11, 10);
	private static final LocalDate NOV_20 = LocalDate.of(2026, 11, 20);
	private static final LocalDate NOV_23 = LocalDate.of(2026, 11, 23);

	@TestConfiguration
	static class FixedClockConfig {
		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(FIXED, ZoneId.of("Asia/Taipei"));
		}
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private QuoteRepository quoteRepository;

	@MockitoBean
	private DividendEventProvider dividendEventProvider;

	@MockitoBean
	private TwseStockDayClient twseStockDayClient;

	@MockitoBean
	private TpexTradingStockClient tpexTradingStockClient;

	@MockitoBean
	private TwseMiIndexClient twseMiIndexClient;

	@MockitoBean
	private TpexOtcClient tpexOtcClient;

	@MockitoBean
	private MarketResolver marketResolver;

	@AfterEach
	void cleanUp() {
		holdingRepository.deleteAll();
		quoteRepository.deleteAll();
	}

	private Holding holding(String code, String name) {
		return holdingRepository.saveAndFlush(new Holding(code, name, Market.TWSE, NOW));
	}

	private Trade trade(Holding holding, TradeSide side, LocalDate date, long shares, String price) {
		return tradeRepository
				.saveAndFlush(new Trade(holding.getId(), side, date, shares, new BigDecimal(price), false, NOW));
	}

	private void quote(String code, String name, String closePrice) {
		quoteRepository.saveAndFlush(
				new Quote(DailyQuote.ofDay(code, name, Market.TWSE, NOV_23, new BigDecimal(closePrice)), NOW));
	}

	/**
	 * 三檔：
	 * - 2330：11/02 買 2,000 股 @1,000、11/10 除息每股 5 元、11/20 賣 1,000 股 @1,100，收盤價 1,100
	 * - 2317：買 1,000 @100 再全部賣出 @110（已賣光），沒有收盤價
	 * - 2454：買 1,000 @500，沒有收盤價
	 */
	private Holding prepareThreeHoldings() {
		quote("2330", "台積電", "1100.00");
		Holding tsmc = holding("2330", "台積電");
		trade(tsmc, TradeSide.BUY, NOV_02, 2000, "1000.00");
		trade(tsmc, TradeSide.SELL, NOV_20, 1000, "1100.00");

		Holding foxconn = holding("2317", "鴻海");
		trade(foxconn, TradeSide.BUY, NOV_02, 1000, "100.00");
		trade(foxconn, TradeSide.SELL, NOV_20, 1000, "110.00");

		Holding mtk = holding("2454", "聯發科");
		trade(mtk, TradeSide.BUY, NOV_02, 1000, "500.00");

		given(dividendEventProvider.eventsByCode(any())).willReturn(Map.of("2330", List.of(
				new DividendEventInput(NOV_10, DividendKind.CASH, new BigDecimal("5"), BigDecimal.ZERO,
						DividendSource.OFFICIAL))));
		return tsmc;
	}

	@Test
	void 清單_含已賣光和沒有收盤價的持股_數字照計算規則() throws Exception {
		prepareThreeHoldings();

		String body = mockMvc.perform(get("/api/v2/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().contentType(ApiJson.JSON_UTF8))
				.andExpect(jsonPath("$", hasSize(3)))
				// 依代號排序：2317、2330、2454
				// 2317 已賣光：買進成本 100,000 + 142 = 100,142；賣出 110,000 − 156 − 330 − 100,142 = 9,372
				.andExpect(jsonPath("$[0].code").value("2317"))
				.andExpect(jsonPath("$[0].shares").value(0))
				.andExpect(jsonPath("$[0].total_cost").value(0))
				.andExpect(jsonPath("$[0].market_value").value(0))
				.andExpect(jsonPath("$[0].unrealized_pnl").value(0))
				.andExpect(jsonPath("$[0].realized_pnl").value(9372))
				.andExpect(jsonPath("$[0].total_return").value(9372))
				// 2330：成本 2,000,000 + 2,850 = 2,002,850；賣一半扣掉成本 1,001,425
				// 已實現 = 1,100,000 − 1,567 − 3,300 − 1,001,425 = 93,708；未實現同樣是 93,708
				// 現金股利 2,000 × 5 = 10,000；含息總報酬 93,708 + 93,708 + 10,000 = 197,416
				.andExpect(jsonPath("$[1].code").value("2330"))
				.andExpect(jsonPath("$[1].shares").value(1000))
				.andExpect(jsonPath("$[1].total_cost").value(1001425))
				.andExpect(jsonPath("$[1].price_date").value("2026-11-23"))
				.andExpect(jsonPath("$[1].market_value").value(1100000))
				.andExpect(jsonPath("$[1].unrealized_pnl").value(93708))
				.andExpect(jsonPath("$[1].realized_pnl").value(93708))
				.andExpect(jsonPath("$[1].cash_dividends").value(10000))
				.andExpect(jsonPath("$[1].total_return").value(197416))
				.andExpect(jsonPath("$[1].data_error").value(false))
				// 2454：沒有收盤價，成本 500,000 + 712 = 500,712
				.andExpect(jsonPath("$[2].total_cost").value(500712))
				.andExpect(jsonPath("$[2].average_cost").value(500.71))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		// null 的欄位照樣輸出；價格固定兩位小數
		assertThat(body).contains("\"average_cost\":null", "\"current_price\":null", "\"price_date\":null",
				"\"market_value\":null", "\"unrealized_pnl\":null", "\"total_return\":null",
				"\"average_cost\":1001.43", "\"current_price\":1100.00");
	}

	@Test
	void 單筆_清單欄位加上交易和除權息明細() throws Exception {
		Holding tsmc = prepareThreeHoldings();

		mockMvc.perform(get("/api/v2/holdings/{id}", tsmc.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(tsmc.getId()))
				.andExpect(jsonPath("$.total_return").value(197416))
				.andExpect(jsonPath("$.dividend_status").isString())
				.andExpect(jsonPath("$.trades", hasSize(2)))
				.andExpect(jsonPath("$.trades[0].side").value("BUY"))
				.andExpect(jsonPath("$.trades[0].date").value("2026-11-02"))
				.andExpect(jsonPath("$.trades[0].shares").value(2000))
				.andExpect(jsonPath("$.trades[0].price").value(1000.00))
				.andExpect(jsonPath("$.trades[0].date_estimated").value(false))
				.andExpect(jsonPath("$.trades[0].version").value(0))
				.andExpect(jsonPath("$.dividends", hasSize(1)))
				.andExpect(jsonPath("$.dividends[0].ex_date").value("2026-11-10"))
				.andExpect(jsonPath("$.dividends[0].kind").value("CASH"))
				.andExpect(jsonPath("$.dividends[0].eligible_shares").value(2000))
				.andExpect(jsonPath("$.dividends[0].cash").value(10000))
				.andExpect(jsonPath("$.dividends[0].stock_shares").value(0))
				.andExpect(jsonPath("$.dividends[0].source").value("OFFICIAL"));

		// 已賣光的持股用 id 查得到（v1 是 404）；不存在的 id 是 404
		Holding soldOut = holdingRepository.findAll().stream().filter(h -> h.getCode().equals("2317")).findFirst()
				.orElseThrow();
		mockMvc.perform(get("/api/v2/holdings/{id}", soldOut.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shares").value(0));
		mockMvc.perform(get("/api/v2/holdings/{id}", 999999))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void 總計_市值未實現只加有收盤價的_已實現和股利含已賣光的() throws Exception {
		prepareThreeHoldings();

		// 總成本 1,001,425 + 0 + 500,712 = 1,502,137；市值、未實現只有 2330
		// 已實現 93,708 + 9,372 = 103,080；含息總報酬 103,080 + 93,708 + 10,000 = 206,788
		mockMvc.perform(get("/api/v2/summary"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total_cost").value(1502137))
				.andExpect(jsonPath("$.market_value").value(1100000))
				.andExpect(jsonPath("$.unrealized_pnl").value(93708))
				.andExpect(jsonPath("$.realized_pnl").value(103080))
				.andExpect(jsonPath("$.cash_dividends").value(10000))
				.andExpect(jsonPath("$.total_return").value(206788))
				.andExpect(jsonPath("$.quote_missing").value(1))
				.andExpect(jsonPath("$.dividend_pending").isNumber())
				.andExpect(jsonPath("$.data_error").value(0));
	}

	@Test
	void 資料異常_清單標示_單筆回200附交易_總計不計入() throws Exception {
		Holding broken = holding("2603", "長榮");
		// 繞過 TradeService 直接寫入：只有賣出，重播時超賣（模擬資料被手動改過）
		trade(broken, TradeSide.SELL, NOV_20, 1000, "200.00");

		String body = mockMvc.perform(get("/api/v2/holdings"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].data_error").value(true))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).contains("\"shares\":null", "\"total_cost\":null", "\"realized_pnl\":null");

		mockMvc.perform(get("/api/v2/holdings/{id}", broken.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data_error").value(true))
				.andExpect(jsonPath("$.trades", hasSize(1)))
				.andExpect(jsonPath("$.dividends", hasSize(0)));

		mockMvc.perform(get("/api/v2/summary"))
				.andExpect(jsonPath("$.total_cost").value(0))
				.andExpect(jsonPath("$.data_error").value(1));
	}
}
