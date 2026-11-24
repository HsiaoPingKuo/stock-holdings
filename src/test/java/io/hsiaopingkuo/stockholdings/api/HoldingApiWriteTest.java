package io.hsiaopingkuo.stockholdings.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.entity.Quote;
import io.hsiaopingkuo.stockholdings.exception.MarketAmbiguousException;
import io.hsiaopingkuo.stockholdings.exception.MarketUnavailableException;
import io.hsiaopingkuo.stockholdings.exception.StockCodeNotFoundException;
import io.hsiaopingkuo.stockholdings.quote.DailyQuote;
import io.hsiaopingkuo.stockholdings.quote.Deadline;
import io.hsiaopingkuo.stockholdings.quote.MarketResolver;
import io.hsiaopingkuo.stockholdings.quote.StockLookup;
import io.hsiaopingkuo.stockholdings.quote.TpexOtcClient;
import io.hsiaopingkuo.stockholdings.quote.TpexTradingStockClient;
import io.hsiaopingkuo.stockholdings.quote.TwseMiIndexClient;
import io.hsiaopingkuo.stockholdings.quote.TwseStockDayClient;
import io.hsiaopingkuo.stockholdings.repository.HoldingRepository;
import io.hsiaopingkuo.stockholdings.repository.QuoteRepository;
import io.hsiaopingkuo.stockholdings.repository.TradeRepository;
import io.hsiaopingkuo.stockholdings.support.TestHoldings;

/**
 * App 1.0 API 的新增、修改、刪除（第 5 題）。
 * 和 HoldingApiControllerTest 用相同的設定（同一個測試環境會被重複使用）：真的資料庫、真的 JSON 設定，
 * 官方 client 和 MarketResolver 換成假的，測試不會連網。
 *
 * 重點是「型別要嚴格」：App 不會先檢查輸入，使用者填什麼就送什麼，
 * 張數 1.5 不可以被截成 1、字串 "1000" 不可以被自動轉成數字。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:holding-api;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class HoldingApiWriteTest {

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

	private void saveQuote(String code, String name, Market market, String closePrice) {
		quoteRepository.save(new Quote(DailyQuote.ofDay(code, name, market, NOV_23, new BigDecimal(closePrice)), NOW));
	}

	private static RequestBuilder postJson(String json) {
		return post("/api/v1/holdings").contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private static RequestBuilder putJson(Object id, String json) {
		return put("/api/v1/holdings/{id}", id).contentType(MediaType.APPLICATION_JSON).content(json);
	}

	/** 呼叫 API 不會建立 session：API 不依賴網頁的 session，之後網頁加 CSRF 也不會影響 App。 */
	private ResultActions perform(RequestBuilder request) throws Exception {
		ResultActions actions = mockMvc.perform(request);
		assertThat(actions.andReturn().getRequest().getSession(false)).as("API 不應建立 session").isNull();
		return actions;
	}

	/** 錯誤回應的共通檢查：規格的錯誤格式，Content-Type 也帶 UTF-8。 */
	private static ResultActions expectError(ResultActions actions, String code) throws Exception {
		return actions
				.andExpect(content().contentType(ApiJson.JSON_UTF8))
				.andExpect(jsonPath("$.error.code").value(code))
				.andExpect(jsonPath("$.error.message").isString());
	}

	private static String body(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	// ===== POST /api/v1/holdings =====

	@Test
	void 新增_題目例子2330_回201與持股物件_pnl是整數93708() throws Exception {
		// 行情快取沒有 2330，問官方（假的）：上市、11/23 收盤 1100.00
		given(marketResolver.resolve(eq("2330"), any(Deadline.class)))
				.willReturn(StockLookup.found(Market.TWSE, "台積電", NOV_23, new BigDecimal("1100.00")));

		// buy_price 送整數 1000：規格的範例就是這樣送，要接受
		MvcResult result = perform(postJson("{\"code\":\"2330\",\"lots\":1,\"buy_price\":1000}"))
				.andExpect(status().isCreated())
				.andExpect(content().contentType(ApiJson.JSON_UTF8))
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.code").value("2330"))
				.andExpect(jsonPath("$.name").value("台積電"))
				.andExpect(jsonPath("$.market").value("TWSE"))
				.andExpect(jsonPath("$.lots").value(1))
				.andExpect(jsonPath("$.buy_price").isNumber())
				.andExpect(jsonPath("$.current_price").isNumber())
				.andExpect(jsonPath("$.price_date").value("2026-11-23"))
				.andExpect(jsonPath("$.pnl").isNumber())
				.andExpect(jsonPath("$.pnl").value(93708))
				.andExpect(jsonPath("$.return_pct").isNumber())
				.andExpect(jsonPath("$.return_pct").value(9.36))
				.andReturn();

		// 用原始字串確認：pnl 是 93708（不是 93708.00）；價格是數字 1000.00，不是字串
		assertThat(body(result)).contains("\"buy_price\":1000.00,", "\"current_price\":1100.00,", "\"pnl\":93708,",
				"\"return_pct\":9.36");
		assertThat(holdingRepository.findAll()).singleElement().satisfies(h -> {
			assertThat(h.getCode()).isEqualTo("2330");
			assertThat(h.getMarket()).isEqualTo(Market.TWSE);
		});
	}

	@Test
	void 新增_行情快取有這個代號時不問官方_代號去空白轉大寫() throws Exception {
		saveQuote("00631L", "元大台灣50正2", Market.TWSE, "250.00");

		perform(postJson("{\"code\":\" 00631l \",\"lots\":2,\"buy_price\":240.5}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.code").value("00631L"))
				.andExpect(jsonPath("$.market").value("TWSE"))
				.andExpect(jsonPath("$.buy_price").value(240.50));

		verify(marketResolver, never()).resolve(anyString(), any(Deadline.class));
	}

	@Test
	void 新增_多送的欄位直接忽略() throws Exception {
		saveQuote("2330", "台積電", Market.TWSE, "1100.00");

		perform(postJson("{\"code\":\"2330\",\"lots\":1,\"buy_price\":1000,\"note\":\"x\",\"id\":999}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.code").value("2330"));
	}

	/** 400 VALIDATION_ERROR 的各種請求內容，以及 App 會看到的訊息。 */
	static Stream<Arguments> invalidCreateBodies() {
		return Stream.of(
				// 型別不對：不可以自動轉換
				Arguments.of("張數 1.5 不可以被截成 1", "{\"code\":\"2330\",\"lots\":1.5,\"buy_price\":1000}", "張數必須是 1～999 的整數"),
				Arguments.of("張數 1.0 也不是整數", "{\"code\":\"2330\",\"lots\":1.0,\"buy_price\":1000}", "張數必須是 1～999 的整數"),
				Arguments.of("張數是字串", "{\"code\":\"2330\",\"lots\":\"1\",\"buy_price\":1000}", "張數必須是 1～999 的整數"),
				Arguments.of("張數是布林值", "{\"code\":\"2330\",\"lots\":true,\"buy_price\":1000}", "張數必須是 1～999 的整數"),
				Arguments.of("張數超出 int 範圍", "{\"code\":\"2330\",\"lots\":99999999999,\"buy_price\":1000}", "張數必須是 1～999 的整數"),
				Arguments.of("張數是陣列", "{\"code\":\"2330\",\"lots\":[1],\"buy_price\":1000}", "張數必須是 1～999 的整數"),
				Arguments.of("買進價是字串", "{\"code\":\"2330\",\"lots\":1,\"buy_price\":\"1000\"}", "買進價必須是數字，最多兩位小數"),
				Arguments.of("買進價是布林值", "{\"code\":\"2330\",\"lots\":1,\"buy_price\":true}", "買進價必須是數字，最多兩位小數"),
				Arguments.of("代號是數字", "{\"code\":2330,\"lots\":1,\"buy_price\":1000}", "股票代號必須是文字，例如 \"2330\""),
				Arguments.of("代號是布林值", "{\"code\":true,\"lots\":1,\"buy_price\":1000}", "股票代號必須是文字，例如 \"2330\""),
				// 欄位檢查
				Arguments.of("買進價超過兩位小數", "{\"code\":\"2330\",\"lots\":1,\"buy_price\":980.555}", "買進價最多兩位小數（整數部分最多 10 位）"),
				Arguments.of("買進價是 0", "{\"code\":\"2330\",\"lots\":1,\"buy_price\":0}", "買進價必須大於 0"),
				Arguments.of("買進價是負數", "{\"code\":\"2330\",\"lots\":1,\"buy_price\":-1}", "買進價必須大於 0"),
				Arguments.of("張數是 0", "{\"code\":\"2330\",\"lots\":0,\"buy_price\":1000}", "張數必須是 1～999 的整數"),
				Arguments.of("張數是 1000", "{\"code\":\"2330\",\"lots\":1000,\"buy_price\":1000}", "張數必須是 1～999 的整數"),
				Arguments.of("缺少張數", "{\"code\":\"2330\",\"buy_price\":1000}", "請輸入張數"),
				Arguments.of("張數是 null", "{\"code\":\"2330\",\"lots\":null,\"buy_price\":1000}", "請輸入張數"),
				Arguments.of("缺少買進價", "{\"code\":\"2330\",\"lots\":1}", "請輸入買進價"),
				Arguments.of("缺少代號", "{\"lots\":1,\"buy_price\":1000}", "請輸入股票代號"),
				Arguments.of("代號只有空白", "{\"code\":\"   \",\"lots\":1,\"buy_price\":1000}", "請輸入股票代號"),
				Arguments.of("代號有符號", "{\"code\":\"23-30\",\"lots\":1,\"buy_price\":1000}", "股票代號只能是英文字母或數字"),
				Arguments.of("代號超過 10 個字", "{\"code\":\"12345678901\",\"lots\":1,\"buy_price\":1000}", "股票代號最多 10 個字"),
				Arguments.of("全部都沒填：依欄位順序串成一句", "{}", "請輸入股票代號；請輸入張數；請輸入買進價"),
				// JSON 本身壞掉
				Arguments.of("JSON 少了結尾", "{\"code\":\"2330\",\"lots\":1,", "請求內容不是正確的 JSON"),
				Arguments.of("不是 JSON", "code=2330&lots=1", "請求內容不是正確的 JSON"),
				Arguments.of("空的內容", "", "請求內容不是正確的 JSON"),
				Arguments.of("JSON 是 null", "null", "請求內容不是正確的 JSON"),
				Arguments.of("JSON 是陣列", "[]", "請求內容不是正確的 JSON"),
				Arguments.of("JSON 後面還有多的內容", "{\"code\":\"2330\",\"lots\":1,\"buy_price\":1000} x", "請求內容不是正確的 JSON"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidCreateBodies")
	void 新增_請求內容不對_回400_VALIDATION_ERROR_不儲存也不問官方(String description, String json, String message)
			throws Exception {
		expectError(perform(postJson(json)).andExpect(status().isBadRequest()), "VALIDATION_ERROR")
				.andExpect(jsonPath("$.error.message").value(message));

		assertThat(holdingRepository.count()).isZero();
		verify(marketResolver, never()).resolve(anyString(), any(Deadline.class));
	}

	@Test
	void 新增_ContentType不對或沒帶_回400_VALIDATION_ERROR() throws Exception {
		String json = "{\"code\":\"2330\",\"lots\":1,\"buy_price\":1000}";
		expectError(perform(post("/api/v1/holdings").contentType(MediaType.TEXT_PLAIN).content(json))
				.andExpect(status().isBadRequest()), "VALIDATION_ERROR");
		expectError(perform(post("/api/v1/holdings").content(json))
				.andExpect(status().isBadRequest()), "VALIDATION_ERROR");
		assertThat(holdingRepository.count()).isZero();
	}

	@Test
	void 新增_上市上櫃都查不到_回404_STOCK_NOT_FOUND() throws Exception {
		given(marketResolver.resolve(eq("9999"), any(Deadline.class))).willThrow(new StockCodeNotFoundException("9999"));

		expectError(perform(postJson("{\"code\":\"9999\",\"lots\":1,\"buy_price\":10}"))
				.andExpect(status().isNotFound()), "STOCK_NOT_FOUND")
				.andExpect(jsonPath("$.error.message").value("查無此股票代號：9999（上市、上櫃都找不到）"));
		assertThat(holdingRepository.count()).isZero();
	}

	@Test
	void 新增_代號已在持股中_回409_DUPLICATE_CODE_訊息照規格() throws Exception {
		saveHolding("2330", "台積電", Market.TWSE, 1, "1000.00");

		expectError(perform(postJson("{\"code\":\"2330\",\"lots\":2,\"buy_price\":900}"))
				.andExpect(status().isConflict()), "DUPLICATE_CODE")
				.andExpect(jsonPath("$.error.message").value("2330 已在持股中"));
		assertThat(holdingRepository.count()).isEqualTo(1);
	}

	@Test
	void 新增_官方連不上_回503_QUOTE_UNAVAILABLE() throws Exception {
		given(marketResolver.resolve(eq("2330"), any(Deadline.class))).willThrow(new MarketUnavailableException());

		expectError(perform(postJson("{\"code\":\"2330\",\"lots\":1,\"buy_price\":1000}"))
				.andExpect(status().isServiceUnavailable()), "QUOTE_UNAVAILABLE")
				.andExpect(jsonPath("$.error.message").value("暫時無法連線官方網站確認代號，請稍後再試"));
		assertThat(holdingRepository.count()).isZero();
	}

	@Test
	void 新增_兩個市場都查到且日期相同_回503_MARKET_UNDETERMINED() throws Exception {
		given(marketResolver.resolve(eq("1234"), any(Deadline.class))).willThrow(new MarketAmbiguousException("1234"));

		expectError(perform(postJson("{\"code\":\"1234\",\"lots\":1,\"buy_price\":10}"))
				.andExpect(status().isServiceUnavailable()), "MARKET_UNDETERMINED")
				.andExpect(jsonPath("$.error.message").value("無法判斷 1234 是上市或上櫃，請聯絡管理者"));
		assertThat(holdingRepository.count()).isZero();
	}

	// ===== PUT /api/v1/holdings/{id} =====

	@Test
	void 修改_回200與修改後的持股物件() throws Exception {
		Holding h = saveHolding("2330", "台積電", Market.TWSE, 1, "1000.00");
		saveQuote("2330", "台積電", Market.TWSE, "1100.00");

		// 2 張、980.50、收盤 1100：買進 1,961,000 + 手續費 2,794；賣出 2,200,000 − 手續費 3,135 − 證交稅 6,600
		// 盈虧 226,471；收益率 226,471 ÷ 1,963,794 × 100 = 11.532… → 11.53
		MvcResult result = perform(putJson(h.getId(), "{\"lots\":2,\"buy_price\":980.5}"))
				.andExpect(status().isOk())
				.andExpect(content().contentType(ApiJson.JSON_UTF8))
				.andExpect(jsonPath("$.id").value(h.getId()))
				.andExpect(jsonPath("$.code").value("2330"))
				.andExpect(jsonPath("$.lots").value(2))
				.andExpect(jsonPath("$.pnl").value(226471))
				.andExpect(jsonPath("$.return_pct").value(11.53))
				.andReturn();

		assertThat(body(result)).contains("\"buy_price\":980.50,", "\"pnl\":226471,");
		// 第 6 題：張數、買進價記在那一筆買進（使用者同意改檢查交易）：2 張 = 2,000 股
		assertThat(tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(h.getId())).singleElement()
				.satisfies(saved -> {
					assertThat(saved.getShares()).isEqualTo(2000);
					assertThat(saved.getPrice()).isEqualByComparingTo("980.50");
				});
	}

	@Test
	void 修改_請求帶了code時忽略_代號不會變也不回錯誤() throws Exception {
		Holding h = saveHolding("2330", "台積電", Market.TWSE, 1, "1000.00");

		perform(putJson(h.getId(), "{\"code\":\"9999\",\"lots\":3,\"buy_price\":1000}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("2330"))
				.andExpect(jsonPath("$.lots").value(3));

		assertThat(holdingRepository.findById(h.getId()).orElseThrow().getCode()).isEqualTo("2330");
	}

	@Test
	void 修改_請求內容不對_回400_VALIDATION_ERROR_資料不變() throws Exception {
		Holding h = saveHolding("2330", "台積電", Market.TWSE, 1, "1000.00");

		expectError(perform(putJson(h.getId(), "{\"lots\":1.5,\"buy_price\":1000}"))
				.andExpect(status().isBadRequest()), "VALIDATION_ERROR")
				.andExpect(jsonPath("$.error.message").value("張數必須是 1～999 的整數"));
		expectError(perform(putJson(h.getId(), "{\"lots\":2,\"buy_price\":\"980.5\"}"))
				.andExpect(status().isBadRequest()), "VALIDATION_ERROR")
				.andExpect(jsonPath("$.error.message").value("買進價必須是數字，最多兩位小數"));
		expectError(perform(putJson(h.getId(), "{\"lots\":2,\"buy_price\":980.555}"))
				.andExpect(status().isBadRequest()), "VALIDATION_ERROR");
		expectError(perform(putJson(h.getId(), "{\"lots\":2}"))
				.andExpect(status().isBadRequest()), "VALIDATION_ERROR")
				.andExpect(jsonPath("$.error.message").value("請輸入買進價"));
		expectError(perform(putJson(h.getId(), "{\"lots\":2,"))
				.andExpect(status().isBadRequest()), "VALIDATION_ERROR")
				.andExpect(jsonPath("$.error.message").value("請求內容不是正確的 JSON"));

		// 第 6 題：張數、買進價記在那一筆買進（使用者同意改檢查交易）：1 張 = 1,000 股
		assertThat(tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(h.getId())).singleElement()
				.satisfies(unchanged -> {
					assertThat(unchanged.getShares()).isEqualTo(1000);
					assertThat(unchanged.getPrice()).isEqualByComparingTo("1000.00");
				});
	}

	@Test
	void 修改_找不到或id不是數字_回404_NOT_FOUND() throws Exception {
		expectError(perform(putJson(999999, "{\"lots\":2,\"buy_price\":1000}"))
				.andExpect(status().isNotFound()), "NOT_FOUND");
		expectError(perform(putJson("abc", "{\"lots\":2,\"buy_price\":1000}"))
				.andExpect(status().isNotFound()), "NOT_FOUND");
	}

	// ===== DELETE /api/v1/holdings/{id} =====

	@Test
	void 刪除_回204沒有內容_之後查不到() throws Exception {
		Holding h = saveHolding("2330", "台積電", Market.TWSE, 1, "1000.00");

		MvcResult result = perform(delete("/api/v1/holdings/{id}", h.getId()))
				.andExpect(status().isNoContent())
				.andReturn();

		assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
		assertThat(holdingRepository.existsById(h.getId())).isFalse();
		expectError(perform(get("/api/v1/holdings/{id}", h.getId())).andExpect(status().isNotFound()), "NOT_FOUND");
	}

	@Test
	void 刪除_找不到_重複刪除_id不是數字_都回404_NOT_FOUND() throws Exception {
		Holding h = saveHolding("2330", "台積電", Market.TWSE, 1, "1000.00");
		perform(delete("/api/v1/holdings/{id}", h.getId())).andExpect(status().isNoContent());

		expectError(perform(delete("/api/v1/holdings/{id}", h.getId())).andExpect(status().isNotFound()), "NOT_FOUND")
				.andExpect(jsonPath("$.error.message", containsString("找不到這筆持股")));
		expectError(perform(delete("/api/v1/holdings/{id}", "abc")).andExpect(status().isNotFound()), "NOT_FOUND");
	}
}
