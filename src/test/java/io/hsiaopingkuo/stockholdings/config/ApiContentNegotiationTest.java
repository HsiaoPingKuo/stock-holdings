package io.hsiaopingkuo.stockholdings.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import io.hsiaopingkuo.stockholdings.quote.MarketResolver;
import io.hsiaopingkuo.stockholdings.quote.TpexOtcClient;
import io.hsiaopingkuo.stockholdings.quote.TpexTradingStockClient;
import io.hsiaopingkuo.stockholdings.quote.TwseMiIndexClient;
import io.hsiaopingkuo.stockholdings.quote.TwseStockDayClient;

/**
 * App API 一律回 JSON，不看 Accept 標頭（第 5 題）：
 * 主考官可能直接用瀏覽器或其他工具打開 API 網址，客戶端只接受 HTML、XML 等格式時，錯誤仍要是 {"error":{...}}。
 * 網頁（/api/ 以外）仍照 Accept 標頭決定，行為不變。
 *
 * 用和 HoldingApiControllerTest 相同的設定，重複使用同一個測試環境；測試不會連網。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:holding-api;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ApiContentNegotiationTest {

	private static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=UTF-8");

	@Autowired
	private MockMvc mockMvc;

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

	private static void assertJsonError(ResultActions actions, String code) throws Exception {
		actions.andExpect(content().contentType(JSON_UTF8))
				.andExpect(jsonPath("$.error.code").value(code))
				.andExpect(jsonPath("$.error.message").isString());
	}

	// text/html：只接受網頁（沒有 */*）；後兩個是其他工具可能送的
	@ParameterizedTest(name = "Accept: {0}")
	@ValueSource(strings = { "text/html", "application/xml", "text/plain" })
	void Accept不是JSON_API的錯誤仍回JSON錯誤格式(String accept) throws Exception {
		assertJsonError(mockMvc.perform(get("/api/v1/holdings/999").header("Accept", accept))
				.andExpect(status().isNotFound()), "NOT_FOUND");
		assertJsonError(mockMvc.perform(get("/api/v1/xxx").header("Accept", accept))
				.andExpect(status().isNotFound()), "NOT_FOUND");
		assertJsonError(mockMvc.perform(get("/api/v1/holdings/abc").header("Accept", accept))
				.andExpect(status().isNotFound()), "NOT_FOUND");
		assertJsonError(mockMvc.perform(patch("/api/v1/holdings").header("Accept", accept))
				.andExpect(status().isMethodNotAllowed()), "METHOD_NOT_ALLOWED");
		assertJsonError(mockMvc.perform(post("/api/v1/holdings").header("Accept", accept)
				.contentType(MediaType.APPLICATION_JSON).content("{\"lots\":1.5}"))
				.andExpect(status().isBadRequest()), "VALIDATION_ERROR");
	}

	@ParameterizedTest(name = "Accept: {0}")
	@ValueSource(strings = { "text/html", "application/xml" })
	void Accept不是JSON_API成功時也回JSON(String accept) throws Exception {
		mockMvc.perform(get("/api/v1/summary").header("Accept", accept))
				.andExpect(status().isOk())
				.andExpect(content().contentType(JSON_UTF8))
				.andExpect(jsonPath("$.quote_missing").isNumber());
	}

	@ParameterizedTest(name = "Accept: {0}")
	@ValueSource(strings = { "text/html", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8" })
	void 網頁不受影響_清單頁仍是HTML_不存在的網頁不是API的JSON格式(String accept) throws Exception {
		mockMvc.perform(get("/holdings").header("Accept", accept))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));

		MvcResult notFound = mockMvc.perform(get("/no-such-page").header("Accept", accept))
				.andExpect(status().isNotFound())
				.andReturn();
		assertThat(notFound.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("\"error\"");
	}
}
