package io.hsiaopingkuo.stockholdings.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

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
 * 第 6 題第 3 階段：持股改由交易計算後，/api/v1 遇到零股、多筆交易、歸零、配股時的回應（設計決定 2、3、4），
 * 以及資料異常、等不到鎖時不會變成 500。
 * 真的資料庫和 JSON 設定；官方 client、MarketResolver、除權息提供者換成假的，測試不會連網。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:holding-api-v1-trade;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class HoldingApiV1TradeTest {

	// UTC 11/24 17:00 = 台北 11/25 01:00：用台北時區的話「今天」是 11/25，用主機（UTC）時區會錯成 11/24
	private static final Instant FIXED = Instant.parse("2026-11-24T17:00:00Z");
	private static final LocalDate TODAY_TAIPEI = LocalDate.of(2026, 11, 25);
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 14, 0);
	private static final LocalDate NOV_20 = LocalDate.of(2026, 11, 20);
	private static final LocalDate NOV_21 = LocalDate.of(2026, 11, 21);
	private static final LocalDate NOV_23 = LocalDate.of(2026, 11, 23);

	@TestConfiguration
	static class FixedClockConfig {
		// @Primary：程式原本的 Clock（系統時間）還在，需要 Clock 的地方改用這個固定的
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

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PlatformTransactionManager transactionManager;

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
		// 交易由外鍵 ON DELETE CASCADE 一起刪掉
		holdingRepository.deleteAll();
		quoteRepository.deleteAll();
	}

	// ===== 準備資料 =====

	private Holding holding(String code, String name, Market market) {
		return holdingRepository.saveAndFlush(new Holding(code, name, market, NOW));
	}

	private void trade(Holding holding, TradeSide side, LocalDate date, long shares, String price) {
		tradeRepository.saveAndFlush(new Trade(holding.getId(), side, date, shares, new BigDecimal(price), false, NOW));
	}

	private void quote(String code, String name, Market market, String closePrice) {
		quoteRepository.saveAndFlush(new Quote(DailyQuote.ofDay(code, name, market, NOV_23, new BigDecimal(closePrice)), NOW));
	}

	/** 台積電，收盤價 1,100。 */
	private Holding tsmc() {
		quote("2330", "台積電", Market.TWSE, "1100.00");
		return holding("2330", "台積電", Market.TWSE);
	}

	private ResultActions perform(RequestBuilder request) throws Exception {
		return mockMvc.perform(request);
	}

	private static RequestBuilder putJson(long id, String json) {
		return put("/api/v1/holdings/{id}", id).contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private static RequestBuilder postJson(String json) {
		return post("/api/v1/holdings").contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private static String body(ResultActions actions) throws Exception {
		return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	private static ResultActions expectError(ResultActions actions, int status, String code) throws Exception {
		return actions.andExpect(status().is(status))
				.andExpect(content().contentType(ApiJson.JSON_UTF8))
				.andExpect(jsonPath("$.error.code").value(code))
				.andExpect(jsonPath("$.error.message").isString());
	}

	private List<Trade> tradesOf(Holding holding) {
		return tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(holding.getId());
	}

	// ===== 2. 零股 =====

	@Test
	void 零股_1500股_張數1_名稱加註500股_金額用全部股數() throws Exception {
		Holding h = tsmc();
		trade(h, TradeSide.BUY, NOV_20, 1500, "1000.00");

		// 買進 1,500,000 + 手續費 2,137（2,137.5 捨去）= 成本 1,502,137；平均成本 1,001.4246… → 1,001.42
		// 市值 1,650,000 − 賣出手續費 2,351 − 證交稅 4,950 − 成本 1,502,137 = 140,562
		// 收益率 140,562 ÷ 1,502,137 × 100 = 9.3574… → 9.36
		String body = body(perform(get("/api/v1/holdings/{id}", h.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.lots").value(1))
				.andExpect(jsonPath("$.name").value("台積電【請更新 App 2.0：含零股 500 股】"))
				.andExpect(jsonPath("$.pnl").value(140562))
				.andExpect(jsonPath("$.return_pct").value(9.36)));
		assertThat(body).contains("\"buy_price\":1001.42,", "\"pnl\":140562,");
	}

	@Test
	void 零股_只剩300股_張數0_名稱加註300股() throws Exception {
		Holding h = tsmc();
		trade(h, TradeSide.BUY, NOV_20, 300, "1000.00");

		// 成本 300,000 + 427 = 300,427；市值 330,000 − 470 − 990 − 300,427 = 28,113
		perform(get("/api/v1/holdings"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].lots").value(0))
				.andExpect(jsonPath("$[0].name").value("台積電【請更新 App 2.0：含零股 300 股】"))
				.andExpect(jsonPath("$[0].pnl").value(28113));
	}

	@Test
	void 零股_7股乘10點55_成交金額有小數也不會500() throws Exception {
		quote("2363", "矽統", Market.TWSE, "11.00");
		Holding h = holding("2363", "矽統", Market.TWSE);
		trade(h, TradeSide.BUY, NOV_20, 7, "10.55");

		// 成交金額 73.85 → 73；手續費最低 20 → 成本 93；平均成本 93 ÷ 7 = 13.2857… → 13.29
		// 市值 77 − 手續費 20 − 證交稅 0（0.231 捨去）− 成本 93 = −36；收益率 −36 ÷ 93 × 100 = −38.709… → −38.71
		String body = body(perform(get("/api/v1/holdings/{id}", h.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.lots").value(0))
				.andExpect(jsonPath("$.name").value("矽統【請更新 App 2.0：含零股 7 股】"))
				.andExpect(jsonPath("$.pnl").value(-36))
				.andExpect(jsonPath("$.return_pct").value(-38.71)));
		assertThat(body).contains("\"buy_price\":13.29,");
		perform(get("/api/v1/summary"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total_cost").value(93))
				.andExpect(jsonPath("$.market_value").value(77))
				.andExpect(jsonPath("$.pnl").value(-36));
	}

	// ===== 3. 股數為 0 =====

	@Test
	void 股數為0_清單總計不顯示_GET_PUT_DELETE都回404() throws Exception {
		Holding soldOut = tsmc();
		trade(soldOut, TradeSide.BUY, NOV_20, 1000, "1000.00");
		trade(soldOut, TradeSide.SELL, NOV_21, 1000, "1100.00");

		perform(get("/api/v1/holdings")).andExpect(status().isOk()).andExpect(content().json("[]"));
		perform(get("/api/v1/summary"))
				.andExpect(jsonPath("$.total_cost").value(0))
				.andExpect(jsonPath("$.pnl").value(0))
				.andExpect(jsonPath("$.quote_missing").value(0));
		expectError(perform(get("/api/v1/holdings/{id}", soldOut.getId())), 404, "NOT_FOUND");
		expectError(perform(putJson(soldOut.getId(), "{\"lots\":1,\"buy_price\":1000}")), 404, "NOT_FOUND");
		expectError(perform(delete("/api/v1/holdings/{id}", soldOut.getId())), 404, "NOT_FOUND");
		// 交易都還在：404 不是因為被刪掉
		assertThat(tradesOf(soldOut)).hasSize(2);
	}

	// ===== 4. buy_price =====

	@Test
	void buy_price_單筆整張買進是原始成交價_多筆是平均成本() throws Exception {
		Holding single = tsmc();
		trade(single, TradeSide.BUY, NOV_20, 2000, "980.50");
		quote("0050", "元大台灣50", Market.TWSE, "150.00");
		Holding multi = holding("0050", "元大台灣50", Market.TWSE);
		trade(multi, TradeSide.BUY, NOV_20, 1000, "100.00");
		trade(multi, TradeSide.BUY, NOV_21, 1000, "101.00");

		// 多筆：(100,000 + 142) + (101,000 + 143) = 201,285；平均 201,285 ÷ 2,000 = 100.6425 → 100.64
		String body = body(perform(get("/api/v1/holdings")).andExpect(status().isOk()));

		assertThat(body).contains("\"code\":\"2330\",\"name\":\"台積電\",\"market\":\"TWSE\",\"lots\":2,\"buy_price\":980.50,");
		assertThat(body).contains("\"code\":\"0050\",\"name\":\"元大台灣50\",\"market\":\"TWSE\",\"lots\":2,\"buy_price\":100.64,");
	}

	// ===== 5. 修改、刪除 =====

	@Test
	void 修改_單筆整張買進_只改那筆的股數和價格_日期和推估標記不變() throws Exception {
		Holding h = tsmc();
		tradeRepository.saveAndFlush(new Trade(h.getId(), TradeSide.BUY, NOV_20, 1000, new BigDecimal("1000.00"), true, NOW));

		perform(putJson(h.getId(), "{\"lots\":3,\"buy_price\":990.5}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.lots").value(3))
				.andExpect(jsonPath("$.buy_price").value(990.50));

		assertThat(tradesOf(h)).singleElement().satisfies(t -> {
			assertThat(t.getShares()).isEqualTo(3000);
			assertThat(t.getPrice()).isEqualByComparingTo("990.50");
			assertThat(t.getTradeDate()).isEqualTo(NOV_20);
			assertThat(t.isDateEstimated()).isTrue();
			// 樂觀鎖的版本號由 Hibernate 加 1
			assertThat(t.getVersion()).isEqualTo(1);
		});
	}

	@Test
	void 多筆交易_修改刪除都回409_UPGRADE_REQUIRED_資料不變() throws Exception {
		Holding h = tsmc();
		trade(h, TradeSide.BUY, NOV_20, 1000, "1000.00");
		trade(h, TradeSide.BUY, NOV_21, 1000, "1010.00");

		expectError(perform(putJson(h.getId(), "{\"lots\":1,\"buy_price\":1000}")), 409, "UPGRADE_REQUIRED")
				.andExpect(jsonPath("$.error.message").value("這筆持股有多筆交易或配股，請使用網頁或 App 2.0 修改"));
		expectError(perform(delete("/api/v1/holdings/{id}", h.getId())), 409, "UPGRADE_REQUIRED");

		assertThat(holdingRepository.existsById(h.getId())).isTrue();
		assertThat(tradesOf(h)).extracting(Trade::getShares).containsExactly(1000L, 1000L);
	}

	@Test
	void 單筆零股買進_修改刪除都回409_UPGRADE_REQUIRED() throws Exception {
		Holding h = tsmc();
		trade(h, TradeSide.BUY, NOV_20, 1500, "1000.00");

		expectError(perform(putJson(h.getId(), "{\"lots\":1,\"buy_price\":1000}")), 409, "UPGRADE_REQUIRED");
		expectError(perform(delete("/api/v1/holdings/{id}", h.getId())), 409, "UPGRADE_REQUIRED");
		assertThat(tradesOf(h)).singleElement().satisfies(t -> assertThat(t.getShares()).isEqualTo(1500));
	}

	@Test
	void 配過股_股數變了_修改刪除都回409_UPGRADE_REQUIRED() throws Exception {
		quote("2884", "玉山金", Market.TWSE, "30.00");
		Holding h = holding("2884", "玉山金", Market.TWSE);
		trade(h, TradeSide.BUY, NOV_20, 1000, "30.00");
		// 假的除權息：11/23 每千股配 1,000 股 → 1,000 股變 2,000 股（整張，排除「零股」這個原因）
		given(dividendEventProvider.eventsByCode(any())).willReturn(Map.of("2884", List.of(new DividendEventInput(
				NOV_23, DividendKind.STOCK, BigDecimal.ZERO, new BigDecimal("1000"), DividendSource.OFFICIAL))));

		perform(get("/api/v1/holdings/{id}", h.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.lots").value(2))
				.andExpect(jsonPath("$.name").value("玉山金"));
		expectError(perform(putJson(h.getId(), "{\"lots\":2,\"buy_price\":30}")), 409, "UPGRADE_REQUIRED");
		expectError(perform(delete("/api/v1/holdings/{id}", h.getId())), 409, "UPGRADE_REQUIRED");
		assertThat(tradesOf(h)).singleElement().satisfies(t -> assertThat(t.getShares()).isEqualTo(1000));
	}

	@Test
	void 刪除_單筆整張買進_持股和交易一起刪掉() throws Exception {
		Holding h = tsmc();
		trade(h, TradeSide.BUY, NOV_20, 1000, "1000.00");

		perform(delete("/api/v1/holdings/{id}", h.getId())).andExpect(status().isNoContent());

		assertThat(holdingRepository.existsById(h.getId())).isFalse();
		assertThat(tradesOf(h)).isEmpty();
	}

	// ===== 6. 新增 =====

	@Test
	void 新增_新代號_持股加一筆推估的買進_日期是台北的今天_舊欄位寫NULL() throws Exception {
		// 行情快取有這個代號：不會呼叫官方
		quote("2330", "台積電", Market.TWSE, "1100.00");

		String body = body(perform(postJson("{\"code\":\"2330\",\"lots\":1,\"buy_price\":1000}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.lots").value(1))
				.andExpect(jsonPath("$.pnl").value(93708)));
		long id = ((Number) JsonPath.read(body, "$.id")).longValue();

		assertThat(tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(id)).singleElement().satisfies(t -> {
			assertThat(t.getSide()).isEqualTo(TradeSide.BUY);
			assertThat(t.getTradeDate()).isEqualTo(TODAY_TAIPEI);
			assertThat(t.isDateEstimated()).isTrue();
			assertThat(t.getShares()).isEqualTo(1000);
			assertThat(t.getPrice()).isEqualByComparingTo("1000");
		});
		// 2.0 不再寫 holding.lots、buy_price
		Map<String, Object> legacy = jdbc.queryForMap("SELECT lots, buy_price FROM holding WHERE id = ?", id);
		assertThat(legacy.get("LOTS")).isNull();
		assertThat(legacy.get("BUY_PRICE")).isNull();
	}

	@Test
	void 新增_代號已有股數_回409_DUPLICATE_CODE_不多一筆交易() throws Exception {
		Holding h = tsmc();
		trade(h, TradeSide.BUY, NOV_20, 1500, "1000.00");

		expectError(perform(postJson("{\"code\":\"2330\",\"lots\":2,\"buy_price\":900}")), 409, "DUPLICATE_CODE")
				.andExpect(jsonPath("$.error.message").value("2330 已在持股中"));
		assertThat(tradesOf(h)).hasSize(1);
	}

	@Test
	void 新增_歸零後再新增_回傳同一個id_多一筆推估的買進() throws Exception {
		Holding h = tsmc();
		trade(h, TradeSide.BUY, NOV_20, 1000, "1000.00");
		trade(h, TradeSide.SELL, NOV_21, 1000, "1100.00");

		// 歸零時成本強制歸零，重新買回的成本只有這一筆：1,800,000 + 2,565 = 1,802,565；平均 901.2825 → 901.28
		String body = body(perform(postJson("{\"code\":\"2330\",\"lots\":2,\"buy_price\":900}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(h.getId()))
				.andExpect(jsonPath("$.lots").value(2)));
		assertThat(body).contains("\"buy_price\":901.28,");

		assertThat(holdingRepository.count()).isEqualTo(1);
		List<Trade> trades = tradesOf(h);
		assertThat(trades).hasSize(3);
		assertThat(trades.get(2)).satisfies(t -> {
			assertThat(t.getSide()).isEqualTo(TradeSide.BUY);
			assertThat(t.getTradeDate()).isEqualTo(TODAY_TAIPEI);
			assertThat(t.isDateEstimated()).isTrue();
			assertThat(t.getShares()).isEqualTo(2000);
		});
		// 剛買回的持股有多筆交易，App 1.0 不能修改（接受這個限制，設計決定 4）
		expectError(perform(putJson(h.getId(), "{\"lots\":2,\"buy_price\":900}")), 409, "UPGRADE_REQUIRED");
	}

	// ===== 7. 總計 =====

	@Test
	void 總計_等於清單每一檔捨入後的數字加總() throws Exception {
		Holding odd = tsmc();
		trade(odd, TradeSide.BUY, NOV_20, 1500, "1000.00");
		quote("2363", "矽統", Market.TWSE, "11.00");
		Holding tiny = holding("2363", "矽統", Market.TWSE);
		trade(tiny, TradeSide.BUY, NOV_20, 7, "10.55");
		// 沒有收盤價的：只算進成本和 quote_missing
		Holding noQuote = holding("6488", "環球晶", Market.TPEX);
		trade(noQuote, TradeSide.BUY, NOV_20, 1000, "450.00");

		String list = body(perform(get("/api/v1/holdings")).andExpect(status().isOk()));
		List<Number> pnls = JsonPath.read(list, "$[*].pnl");
		long sumOfPnl = pnls.stream().filter(p -> p != null).mapToLong(Number::longValue).sum();

		// 140,562 + (−36) = 140,526；成本 1,502,137 + 93 + 450,641 = 1,952,871；市值 1,650,000 + 77
		// 收益率 140,526 ÷ 1,952,871 × 100 = 7.1958… → 7.20
		perform(get("/api/v1/summary"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.pnl").value(sumOfPnl))
				.andExpect(jsonPath("$.pnl").value(140526))
				.andExpect(jsonPath("$.total_cost").value(1952871))
				.andExpect(jsonPath("$.market_value").value(1650077))
				.andExpect(jsonPath("$.return_pct").value(7.20))
				.andExpect(jsonPath("$.quote_missing").value(1));
	}

	// ===== 資料異常（重播時超賣）=====

	@Test
	void 資料異常_清單總計不列出_單筆修改刪除回409_DATA_ERROR_網頁顯示資料異常() throws Exception {
		Holding ok = tsmc();
		trade(ok, TradeSide.BUY, NOV_20, 1000, "1000.00");
		// 模擬資料被手動改過：只有一筆賣出，重播時超賣
		Holding broken = holding("0050", "元大台灣50", Market.TWSE);
		trade(broken, TradeSide.SELL, NOV_20, 1000, "150.00");

		perform(get("/api/v1/holdings"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].code").value("2330"));
		perform(get("/api/v1/summary"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.pnl").value(93708))
				.andExpect(jsonPath("$.quote_missing").value(0));
		expectError(perform(get("/api/v1/holdings/{id}", broken.getId())), 409, "DATA_ERROR")
				.andExpect(jsonPath("$.error.message").value("這筆持股的交易資料異常，請使用網頁檢查"));
		expectError(perform(putJson(broken.getId(), "{\"lots\":1,\"buy_price\":150}")), 409, "DATA_ERROR");
		expectError(perform(delete("/api/v1/holdings/{id}", broken.getId())), 409, "DATA_ERROR");
		assertThat(holdingRepository.existsById(broken.getId())).isTrue();

		// 網頁：照樣列出、標示資料異常，總計只算正常的那一檔
		perform(get("/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("資料異常")))
				.andExpect(content().string(containsString("<td id=\"summary-profit\" class=\"num gain\">93,708</td>")));
	}

	// ===== 等不到鎖 =====

	@Test
	void 別的裝置鎖住持股時_修改刪除新增回409_HOLDING_BUSY_網頁顯示同一句_資料不變() throws Exception {
		Holding h = tsmc();
		trade(h, TradeSide.BUY, NOV_20, 1000, "1000.00");
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService otherDevice = Executors.newSingleThreadExecutor();
		try {
			// 另一個執行緒模擬「別的裝置正在修改」：鎖住這一列持股，直到測試做完才放開
			Future<?> holder = otherDevice.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
					status -> {
						holdingRepository.findByIdForUpdate(h.getId()).orElseThrow();
						locked.countDown();
						try {
							release.await(30, TimeUnit.SECONDS);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
					}));
			assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

			long start = System.nanoTime();
			expectError(perform(putJson(h.getId(), "{\"lots\":2,\"buy_price\":900}")), 409, "HOLDING_BUSY")
					.andExpect(jsonPath("$.error.message").value("這筆持股正在被其他裝置修改，請稍後再試"));
			// 有真的等鎖（H2 預設 2 秒），不是一開始就失敗
			assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(1500));
			expectError(perform(delete("/api/v1/holdings/{id}", h.getId())), 409, "HOLDING_BUSY");
			expectError(perform(postJson("{\"code\":\"2330\",\"lots\":1,\"buy_price\":900}")), 409, "HOLDING_BUSY");
			perform(post("/holdings/{id}/delete", h.getId()))
					.andExpect(redirectedUrl("/holdings"))
					.andExpect(flash().attribute("errorMessage", "這筆持股正在被其他裝置修改，請稍後再試"));

			release.countDown();
			holder.get(10, TimeUnit.SECONDS);
		} finally {
			release.countDown();
			otherDevice.shutdownNow();
		}
		assertThat(tradesOf(h)).singleElement().satisfies(t -> assertThat(t.getShares()).isEqualTo(1000));
	}
}
