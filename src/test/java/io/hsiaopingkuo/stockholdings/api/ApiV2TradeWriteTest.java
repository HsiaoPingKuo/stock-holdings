package io.hsiaopingkuo.stockholdings.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

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
import io.hsiaopingkuo.stockholdings.repository.IdempotencyRecordRepository;
import io.hsiaopingkuo.stockholdings.repository.QuoteRepository;
import io.hsiaopingkuo.stockholdings.repository.TradeRepository;
import io.hsiaopingkuo.stockholdings.service.DividendEventProvider;

/**
 * 第 6 題 /api/v2 的交易寫入：新增、修改、刪除，Idempotency-Key（缺少、重送、內容不同），
 * OVERSELL、VERSION_CONFLICT 的錯誤格式。
 * 真的資料庫和 JSON 設定；行情快取先放好代號，新增全新代號時不會查官方；官方 client 換成假的，測試不會連網。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:api-v2-trade-write;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ApiV2TradeWriteTest {

	// 台北 2026-11-25（週三）14:00
	private static final Instant FIXED = Instant.parse("2026-11-25T06:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 14, 0);
	private static final LocalDate NOV_23 = LocalDate.of(2026, 11, 23);

	private static final String BUY_2330 = """
			{"code":"2330","side":"BUY","date":"2026-11-20","shares":1500,"price":1000}""";

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

	@Autowired
	private IdempotencyRecordRepository idempotencyRecordRepository;

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
		idempotencyRecordRepository.deleteAll();
	}

	// ===== 準備資料、送請求 =====

	/** 行情快取有 2330（收盤價 1,100）：新增全新代號時從快取知道市場和名稱，不查官方。 */
	private void quote2330() {
		quoteRepository.saveAndFlush(new Quote(
				DailyQuote.ofDay("2330", "台積電", Market.TWSE, NOV_23, new BigDecimal("1100.00")), NOW));
	}

	private ResultActions postTrade(String key, String json) throws Exception {
		MockHttpServletRequestBuilder request = post("/api/v2/trades").contentType(MediaType.APPLICATION_JSON)
				.content(json);
		return mockMvc.perform(key == null ? request : request.header("Idempotency-Key", key));
	}

	private ResultActions putTrade(String key, long id, String json) throws Exception {
		return mockMvc.perform(put("/api/v2/trades/{id}", id).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(json));
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

	private static long longAt(String json, String path) {
		return ((Number) JsonPath.read(json, path)).longValue();
	}

	// ===== 新增 =====

	@Test
	void 新增_全新代號_201_回傳交易和持股的最新狀態() throws Exception {
		quote2330();

		// 成本 1,500,000 + 2,137 = 1,502,137；市值 1,650,000 − 2,351 − 4,950 − 1,502,137 = 140,562
		postTrade("key-create", BUY_2330)
				.andExpect(status().isCreated())
				.andExpect(content().contentType(ApiJson.JSON_UTF8))
				.andExpect(jsonPath("$.trade.id").isNumber())
				.andExpect(jsonPath("$.trade.side").value("BUY"))
				.andExpect(jsonPath("$.trade.date").value("2026-11-20"))
				.andExpect(jsonPath("$.trade.shares").value(1500))
				.andExpect(jsonPath("$.trade.price").value(1000.00))
				.andExpect(jsonPath("$.trade.date_estimated").value(false))
				.andExpect(jsonPath("$.trade.version").value(0))
				.andExpect(jsonPath("$.holding.code").value("2330"))
				.andExpect(jsonPath("$.holding.name").value("台積電"))
				.andExpect(jsonPath("$.holding.shares").value(1500))
				.andExpect(jsonPath("$.holding.total_cost").value(1502137))
				.andExpect(jsonPath("$.holding.unrealized_pnl").value(140562))
				// 寫入回應的 holding 只放清單欄位
				.andExpect(jsonPath("$.holding.trades").doesNotExist());
	}

	// ===== Idempotency-Key =====

	@Test
	void 沒帶或空白的IdempotencyKey_400() throws Exception {
		expectError(postTrade(null, BUY_2330), 400, "IDEMPOTENCY_KEY_REQUIRED");
		expectError(postTrade(" ", BUY_2330), 400, "IDEMPOTENCY_KEY_REQUIRED");
		// 請求內容也有錯時，先回缺少 header
		expectError(postTrade(null, "{}"), 400, "IDEMPOTENCY_KEY_REQUIRED");
		assertThat(tradeRepository.count()).isZero();
	}

	@Test
	void 同一個鍵重送_回傳一模一樣的狀態碼和JSON_只寫入一次() throws Exception {
		quote2330();

		MvcResult first = postTrade("key-retry", BUY_2330).andExpect(status().isCreated()).andReturn();
		// 價格寫成 1000.00 也算同一個內容
		MvcResult second = postTrade("key-retry", BUY_2330.replace("\"price\":1000", "\"price\":1000.00"))
				.andExpect(status().isCreated()).andReturn();

		assertThat(second.getResponse().getContentAsString(StandardCharsets.UTF_8))
				.isEqualTo(first.getResponse().getContentAsString(StandardCharsets.UTF_8));
		assertThat(second.getResponse().getContentType()).isEqualTo(ApiJson.JSON_UTF8_VALUE);
		assertThat(tradeRepository.count()).isEqualTo(1);
	}

	@Test
	void 同一個鍵_內容不同_422() throws Exception {
		quote2330();
		postTrade("key-reuse", BUY_2330).andExpect(status().isCreated());

		expectError(postTrade("key-reuse", BUY_2330.replace("1500", "2000")), 422, "IDEMPOTENCY_KEY_REUSED");
		assertThat(tradeRepository.count()).isEqualTo(1);
	}

	// ===== 修改 =====

	@Test
	void 修改_200_版本號加1_舊版本號回409_VERSION_CONFLICT() throws Exception {
		quote2330();
		String created = body(postTrade("key-u1", BUY_2330));
		long tradeId = longAt(created, "$.trade.id");

		putTrade("key-u2", tradeId, """
				{"date":"2026-11-20","shares":2000,"price":1000,"version":0}""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.trade.id").value(tradeId))
				.andExpect(jsonPath("$.trade.shares").value(2000))
				.andExpect(jsonPath("$.trade.version").value(1))
				.andExpect(jsonPath("$.holding.shares").value(2000));

		// 另一支手機還拿著版本 0
		expectError(putTrade("key-u3", tradeId, """
				{"date":"2026-11-20","shares":1000,"price":1000,"version":0}"""), 409, "VERSION_CONFLICT");
	}

	// ===== 刪除 =====

	@Test
	void 刪除_還有其他交易時回傳持股_刪掉最後一筆時holding是null() throws Exception {
		quote2330();
		String first = body(postTrade("key-d1", BUY_2330));
		String second = body(postTrade("key-d2", BUY_2330.replace("1500", "500")));
		long firstId = longAt(first, "$.trade.id");
		long secondId = longAt(second, "$.trade.id");
		long holdingId = longAt(first, "$.holding.id");

		mockMvc.perform(delete("/api/v2/trades/{id}", secondId).param("version", "0").header("Idempotency-Key",
				"key-d3"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deleted_trade_id").value(secondId))
				.andExpect(jsonPath("$.holding_id").value(holdingId))
				.andExpect(jsonPath("$.holding.shares").value(1500));

		String last = body(mockMvc.perform(delete("/api/v2/trades/{id}", firstId).param("version", "0")
				.header("Idempotency-Key", "key-d4"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.holding_id").value(holdingId)));
		assertThat(last).contains("\"holding\":null");
		assertThat(holdingRepository.existsById(holdingId)).isFalse();
	}

	@Test
	void 刪除_沒帶version_400() throws Exception {
		expectError(mockMvc.perform(delete("/api/v2/trades/{id}", 1).header("Idempotency-Key", "key-dv")), 400,
				"VALIDATION_ERROR");
		expectError(mockMvc.perform(delete("/api/v2/trades/{id}", 1).param("version", "abc")
				.header("Idempotency-Key", "key-dv2")), 400, "VALIDATION_ERROR");
	}

	// ===== 超賣 =====

	@Test
	void 超賣_409_OVERSELL帶兩個交易id_失敗後同一個鍵可以修正後重送() throws Exception {
		quote2330();
		Holding holding = holdingRepository.saveAndFlush(new Holding("2330", "台積電", Market.TWSE, NOW));
		// 1.0 轉入的買進：日期是推估的（建立日期 11/20），實際買進日可能更早
		Trade estimatedBuy = tradeRepository.saveAndFlush(new Trade(holding.getId(), TradeSide.BUY,
				LocalDate.of(2026, 11, 20), 1000, new BigDecimal("1000.00"), true, NOW));

		// 補登 11/10 的賣出：排在推估的買進前面，當時還沒有股票
		String sell = """
				{"code":"2330","side":"SELL","date":"2026-11-10","shares":1000,"price":1100}""";
		String error = body(expectError(postTrade("key-oversell", sell), 409, "OVERSELL"));
		assertThat(error).contains("\"oversold_trade_id\":null",
				"\"estimated_buy_trade_id\":" + estimatedBuy.getId());
		assertThat(tradeRepository.count()).isEqualTo(1);

		// 失敗時冪等鍵不保留：改成實際的日期後，用同一個鍵重送可以成功
		postTrade("key-oversell", sell.replace("2026-11-10", "2026-11-24"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.holding.shares").value(0));
	}
}
