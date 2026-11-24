package io.hsiaopingkuo.stockholdings.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.entity.Quote;
import io.hsiaopingkuo.stockholdings.quote.DailyQuote;
import io.hsiaopingkuo.stockholdings.quote.MarketResolver;
import io.hsiaopingkuo.stockholdings.quote.TpexOtcClient;
import io.hsiaopingkuo.stockholdings.quote.TpexTradingStockClient;
import io.hsiaopingkuo.stockholdings.quote.TwseMiIndexClient;
import io.hsiaopingkuo.stockholdings.quote.TwseStockDayClient;
import io.hsiaopingkuo.stockholdings.repository.HoldingRepository;
import io.hsiaopingkuo.stockholdings.repository.QuoteRepository;
import io.hsiaopingkuo.stockholdings.repository.TradeRepository;
import io.hsiaopingkuo.stockholdings.support.TestHoldings;

/**
 * App 1.0 API（第 5 題）：啟動整個程式，用真的資料庫（記憶體 H2）、真的 JSON 設定和計算，
 * 只把官方 client 和 MarketResolver 換成假的，測試不會連網。
 *
 * 不只檢查值，也檢查每個欄位的「型別」（isNumber、isString、nullValue）：
 * 型別不對或少了欄位，App 會顯示「資料格式錯誤」。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:holding-api;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class HoldingApiControllerTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 14, 0);
	private static final LocalDate NOV_23 = LocalDate.of(2026, 11, 23);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private QuoteRepository quoteRepository;

	@Autowired
	private TradeRepository tradeRepository;

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

	private Holding saveHolding(String code, String name, Market market, int lots, String buyPrice) {
		// 第 6 題：張數、買進價改記在交易，存成「持股＋一筆整張買進」（和 V4 轉換 1.0 資料的方式相同）
		return TestHoldings.saveWithBuy(holdingRepository, tradeRepository, code, name, market, lots, buyPrice, NOW);
	}

	private void saveQuote(String code, String name, Market market, LocalDate date, String closePrice) {
		quoteRepository.save(new Quote(DailyQuote.ofDay(code, name, market, date,
				closePrice == null ? null : new BigDecimal(closePrice)), NOW));
	}

	/** 每個成功回應都要是 application/json;charset=UTF-8，而且呼叫 API 不會建立 session（API 不依賴網頁的 session）。 */
	private ResultActions perform(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
		ResultActions actions = mockMvc.perform(request);
		MvcResult result = actions.andReturn();
		assertThat(result.getRequest().getSession(false)).as("API 不應建立 session").isNull();
		return actions;
	}

	// ===== GET /api/v1/holdings =====

	@Test
	void 清單_沒有持股時回空陣列() throws Exception {
		perform(get("/api/v1/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().contentType(ApiJson.JSON_UTF8))
				.andExpect(content().json("[]"));
	}

	@Test
	void 清單_題目例子2330_每個欄位的值與型別都照規格() throws Exception {
		Holding h = saveHolding("2330", "台積電", Market.TWSE, 1, "1000.00");
		saveQuote("2330", "台積電", Market.TWSE, NOV_23, "1100.00");

		MvcResult result = perform(get("/api/v1/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().contentType(ApiJson.JSON_UTF8))
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].id").isNumber())
				.andExpect(jsonPath("$[0].id").value(h.getId()))
				.andExpect(jsonPath("$[0].code").isString())
				.andExpect(jsonPath("$[0].code").value("2330"))
				.andExpect(jsonPath("$[0].name").value("台積電"))
				.andExpect(jsonPath("$[0].market").value("TWSE"))
				.andExpect(jsonPath("$[0].lots").isNumber())
				.andExpect(jsonPath("$[0].lots").value(1))
				.andExpect(jsonPath("$[0].buy_price").isNumber())
				.andExpect(jsonPath("$[0].buy_price").value(1000.00))
				.andExpect(jsonPath("$[0].current_price").isNumber())
				.andExpect(jsonPath("$[0].current_price").value(1100.00))
				.andExpect(jsonPath("$[0].price_date").value("2026-11-23"))
				.andExpect(jsonPath("$[0].pnl").isNumber())
				.andExpect(jsonPath("$[0].pnl").value(93708))
				.andExpect(jsonPath("$[0].return_pct").isNumber())
				.andExpect(jsonPath("$[0].return_pct").value(9.36))
				.andReturn();

		String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
		// 金額是整數：不可以輸出成 93708.00；價格是數字，不是字串 "1000.00"
		assertThat(body).contains("\"pnl\":93708,", "\"buy_price\":1000.00,", "\"current_price\":1100.00,",
				"\"return_pct\":9.36");
		// 中文用 UTF-8 解碼要正確
		assertThat(body).contains("台積電");
	}

	@Test
	void 清單_沒有收盤價時四個欄位都存在而且是null() throws Exception {
		saveHolding("6488", "環球晶", Market.TPEX, 2, "450.00");

		MvcResult result = perform(get("/api/v1/holdings"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].current_price").hasJsonPath())
				.andExpect(jsonPath("$[0].current_price").value(nullValue()))
				.andExpect(jsonPath("$[0].price_date").hasJsonPath())
				.andExpect(jsonPath("$[0].price_date").value(nullValue()))
				.andExpect(jsonPath("$[0].pnl").hasJsonPath())
				.andExpect(jsonPath("$[0].pnl").value(nullValue()))
				.andExpect(jsonPath("$[0].return_pct").hasJsonPath())
				.andExpect(jsonPath("$[0].return_pct").value(nullValue()))
				.andReturn();

		// 再用原始字串確認：欄位真的有輸出，不是被省略
		assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
				.contains("\"current_price\":null", "\"price_date\":null", "\"pnl\":null", "\"return_pct\":null");
	}

	@Test
	void 清單_當天無成交時price_date是前一個有成交日_和current_price對應() throws Exception {
		saveHolding("2363", "矽統", Market.TWSE, 1, "10.00");
		// 最近交易日 11/24 沒有成交，收盤價沿用 11/23 的
		quoteRepository.save(new Quote(new DailyQuote("2363", "矽統", Market.TWSE, LocalDate.of(2026, 11, 24),
				new BigDecimal("10.50"), NOV_23), NOW));

		perform(get("/api/v1/holdings"))
				.andExpect(jsonPath("$[0].current_price").value(10.50))
				.andExpect(jsonPath("$[0].price_date").value("2026-11-23"));
	}

	@Test
	void 清單_第2題的舊持股沒有名稱市場_用行情快取補_都沒有時輸出空字串而不是null() throws Exception {
		// 第 2 題時存的持股：name、market 都是 NULL
		saveHolding("1101", null, null, 1, "40.00");
		saveQuote("1101", "台泥", Market.TWSE, NOV_23, "41.00");
		// 行情快取也沒有（例如官方一直連不上）
		saveHolding("9999", null, null, 1, "10.00");

		perform(get("/api/v1/holdings"))
				.andExpect(jsonPath("$[0].code").value("1101"))
				.andExpect(jsonPath("$[0].name").value("台泥"))
				.andExpect(jsonPath("$[0].market").value("TWSE"))
				.andExpect(jsonPath("$[1].code").value("9999"))
				.andExpect(jsonPath("$[1].name").isString())
				.andExpect(jsonPath("$[1].name").value(""))
				.andExpect(jsonPath("$[1].market").isString())
				.andExpect(jsonPath("$[1].market").value(""));
	}

	// ===== GET /api/v1/holdings/{id} =====

	@Test
	void 單筆_回傳持股物件() throws Exception {
		Holding h = saveHolding("0050", "元大台灣50", Market.TWSE, 2, "150.00");
		saveQuote("0050", "元大台灣50", Market.TWSE, NOV_23, "145.50");

		perform(get("/api/v1/holdings/{id}", h.getId()))
				.andExpect(status().isOk())
				.andExpect(content().contentType(ApiJson.JSON_UTF8))
				.andExpect(jsonPath("$.id").value(h.getId()))
				.andExpect(jsonPath("$.code").value("0050"))
				.andExpect(jsonPath("$.lots").value(2))
				.andExpect(jsonPath("$.buy_price").value(150.00))
				.andExpect(jsonPath("$.current_price").value(145.50))
				// 題目第 4 題的 ETF 例子
				.andExpect(jsonPath("$.pnl").value(-10132))
				.andExpect(jsonPath("$.return_pct").value(-3.37));
	}

	@Test
	void 單筆_找不到回404_NOT_FOUND() throws Exception {
		perform(get("/api/v1/holdings/{id}", 999999))
				.andExpect(status().isNotFound())
				.andExpect(content().contentType(ApiJson.JSON_UTF8))
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.error.message").isString());
	}

	// ===== GET /api/v1/summary =====

	@Test
	void 總計_照第4題規則_沒有收盤價的只算進成本與quote_missing() throws Exception {
		saveHolding("2330", "台積電", Market.TWSE, 1, "1000.00");
		saveQuote("2330", "台積電", Market.TWSE, NOV_23, "1100.00");
		saveHolding("6488", "環球晶", Market.TPEX, 1, "450.00");

		// 2330：成本 1,000,000 + 1,425 = 1,001,425；市值 1,100,000；盈虧 93,708
		// 6488：沒有收盤價，成本 450,000 + 641 = 450,641
		// 總成本 1,452,066；收益率 93,708 ÷ 1,452,066 × 100 = 6.4535… → 6.45
		MvcResult result = perform(get("/api/v1/summary"))
				.andExpect(status().isOk())
				.andExpect(content().contentType(ApiJson.JSON_UTF8))
				.andExpect(jsonPath("$.total_cost").isNumber())
				.andExpect(jsonPath("$.total_cost").value(1452066))
				.andExpect(jsonPath("$.market_value").value(1100000))
				.andExpect(jsonPath("$.pnl").value(93708))
				.andExpect(jsonPath("$.return_pct").isNumber())
				.andExpect(jsonPath("$.return_pct").value(6.45))
				.andExpect(jsonPath("$.quote_missing").isNumber())
				.andExpect(jsonPath("$.quote_missing").value(1))
				.andReturn();

		assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
				.contains("\"total_cost\":1452066,", "\"market_value\":1100000,", "\"pnl\":93708,");
	}

	@Test
	void 總計_全部都沒有收盤價時回數字0_不是null() throws Exception {
		saveHolding("6488", "環球晶", Market.TPEX, 1, "450.00");

		MvcResult result = perform(get("/api/v1/summary"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total_cost").value(450641))
				.andExpect(jsonPath("$.market_value").isNumber())
				.andExpect(jsonPath("$.market_value").value(0))
				.andExpect(jsonPath("$.pnl").isNumber())
				.andExpect(jsonPath("$.pnl").value(0))
				.andExpect(jsonPath("$.return_pct").isNumber())
				.andExpect(jsonPath("$.quote_missing").value(1))
				.andReturn();

		assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
				.doesNotContain("null")
				.contains("\"return_pct\":0.00");
	}

	@Test
	void 總計_沒有持股時全部是0() throws Exception {
		perform(get("/api/v1/summary"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total_cost").value(0))
				.andExpect(jsonPath("$.market_value").value(0))
				.andExpect(jsonPath("$.pnl").value(0))
				.andExpect(jsonPath("$.return_pct").isNumber())
				.andExpect(jsonPath("$.quote_missing").value(0));
	}

	@Test
	void 網頁清單也用行情快取補上舊持股的市場() throws Exception {
		Holding old = saveHolding("1101", null, null, 1, "40.00");
		saveQuote("1101", "台泥", Market.TWSE, NOV_23, "41.00");

		// 第 6 題：2.0 清單沒有「市場」欄，市場顯示在明細頁的標題
		mockMvc.perform(get("/holdings/{id}", old.getId()))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("台泥")))
				.andExpect(content().string(containsString("（上市）")));
	}
}
