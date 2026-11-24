package io.hsiaopingkuo.stockholdings.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.StringUtils;

import io.hsiaopingkuo.stockholdings.config.CsrfDeniedHandler;
import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.entity.Market;
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
 * CSRF 防護：網頁表單要帶 token，/api 不用。
 * 用完整的 Spring Security 設定（SpringBootTest），設定和 HoldingApiWriteTest 相同，測試環境可以共用；
 * 官方 client 和 MarketResolver 換成假的，測試不會連網。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:holding-api;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class CsrfProtectionTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 14, 0);

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

	private Holding save2330() {
		return TestHoldings.saveWithBuy(holdingRepository, tradeRepository, "2330", "台積電", Market.TWSE, 1, "1000", NOW);
	}

	private void found2330() {
		given(marketResolver.resolve(eq("2330"), any(Deadline.class)))
				.willReturn(StockLookup.found(Market.TWSE, "台積電", LocalDate.of(2026, 11, 23), new BigDecimal("1100.00")));
	}

	/** 被擋時導回清單；用同一個 session 打開清單，要看到「表單已過期」。 */
	private void expectRejectedWithMessage(MvcResult result) throws Exception {
		assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/holdings");
		MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
		mockMvc.perform(get("/holdings").session(session))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(CsrfDeniedHandler.MESSAGE)));
	}

	@Test
	void 現有三個表單_畫面上都帶有_csrf隱藏欄位() throws Exception {
		Holding h = save2330();

		// 清單頁：新增表單＋這筆持股的刪除表單
		String list = mockMvc.perform(get("/holdings")).andReturn().getResponse().getContentAsString();
		assertThat(StringUtils.countOccurrencesOf(list, "name=\"_csrf\"")).isEqualTo(2);
		// 修改頁：修改表單
		String edit = mockMvc.perform(get("/holdings/{id}/edit", h.getId())).andReturn().getResponse().getContentAsString();
		assertThat(StringUtils.countOccurrencesOf(edit, "name=\"_csrf\"")).isEqualTo(1);
	}

	@Test
	void 新增_沒帶token_被擋並顯示表單已過期_資料沒變() throws Exception {
		MvcResult result = mockMvc.perform(post("/holdings").param("code", "2330").param("lots", "1").param("buyPrice", "1000"))
				.andReturn();

		expectRejectedWithMessage(result);
		assertThat(holdingRepository.count()).isZero();
		verifyNoInteractions(marketResolver);
	}

	@Test
	void 修改_token錯誤_被擋並顯示表單已過期_資料沒變() throws Exception {
		Holding h = save2330();

		MvcResult result = mockMvc.perform(post("/holdings/{id}", h.getId()).with(csrf().useInvalidToken())
				.param("lots", "2").param("buyPrice", "980.5")).andReturn();

		expectRejectedWithMessage(result);
		assertThat(tradeRepository.findAll()).singleElement()
				.satisfies(t -> assertThat(t.getShares()).isEqualTo(1000));
	}

	@Test
	void 刪除_沒帶token_被擋並顯示表單已過期_持股還在() throws Exception {
		Holding h = save2330();

		MvcResult result = mockMvc.perform(post("/holdings/{id}/delete", h.getId())).andReturn();

		expectRejectedWithMessage(result);
		assertThat(holdingRepository.existsById(h.getId())).isTrue();
	}

	@Test
	void 新增_帶token_正常新增() throws Exception {
		found2330();

		mockMvc.perform(post("/holdings").with(csrf()).param("code", "2330").param("lots", "1").param("buyPrice", "1000"))
				.andExpect(redirectedUrl("/holdings"))
				.andExpect(flash().attribute("message", "已新增 2330"));
		assertThat(holdingRepository.count()).isEqualTo(1);
	}

	@Test
	void api_v1的新增修改刪除_不用token_行為不變() throws Exception {
		found2330();

		MvcResult created = mockMvc.perform(post("/api/v1/holdings").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"2330\",\"lots\":1,\"buy_price\":1000}"))
				.andExpect(status().isCreated())
				.andReturn();
		Long id = holdingRepository.findAll().get(0).getId();

		mockMvc.perform(put("/api/v1/holdings/{id}", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"lots\":2,\"buy_price\":980.5}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.lots").value(2));

		mockMvc.perform(delete("/api/v1/holdings/{id}", id))
				.andExpect(status().isNoContent());
		assertThat(holdingRepository.count()).isZero();
		// App 不靠 cookie：API 不會因為 CSRF 而建立 session
		assertThat(created.getRequest().getSession(false)).isNull();
	}

	@Test
	void api網址被SpringSecurity擋下_仍回規格的錯誤格式() throws Exception {
		for (String url : new String[] { "/api/v1/holdings;x=1", "/api/v1//holdings" }) {
			// 用 URI 傳入：直接傳字串的話，MockMvc 會先把 // 合併成 /，請求就不含 // 了
			mockMvc.perform(get(URI.create(url)))
					.andExpect(status().isBadRequest())
					.andExpect(content().contentType("application/json;charset=UTF-8"))
					.andExpect(jsonPath("$.error.code").value("BAD_REQUEST"))
					.andExpect(jsonPath("$.error.message").isString());
		}
	}

	@Test
	void 網頁網址被SpringSecurity擋下_維持預設的400() throws Exception {
		mockMvc.perform(get("/holdings;x=1"))
				.andExpect(status().isBadRequest())
				.andExpect(content().string(""));
	}
}
