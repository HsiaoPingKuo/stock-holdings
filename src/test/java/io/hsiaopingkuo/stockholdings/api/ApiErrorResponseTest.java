package io.hsiaopingkuo.stockholdings.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import io.hsiaopingkuo.stockholdings.service.HoldingService;

/**
 * App API 的「所有」錯誤都要是 {"error":{"code":"...","message":"..."}} 格式，
 * 包括路徑不存在、方法不支援、{id} 不是數字、非預期的例外，而且不能露出 stack trace。
 * Service 用假的，才能讓它丟出「非預期的例外」。
 */
@WebMvcTest(HoldingApiController.class)
class ApiErrorResponseTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private HoldingService holdingService;

	/** 共通檢查：錯誤格式、Content-Type 帶 UTF-8、內容沒有 stack trace（例如 "at io.hsiaopingkuo..."、例外類別名稱）。 */
	private static void assertErrorFormat(ResultActions actions, String code) throws Exception {
		MvcResult result = actions
				.andExpect(content().contentType(ApiJson.JSON_UTF8))
				.andExpect(jsonPath("$.error.code").value(code))
				.andExpect(jsonPath("$.error.message").isString())
				.andReturn();
		String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).doesNotContain("at io.", "Exception", "trace");
	}

	@Test
	void 路徑不存在_回404_NOT_FOUND() throws Exception {
		assertErrorFormat(mockMvc.perform(get("/api/v1/no-such-api")).andExpect(status().isNotFound()), "NOT_FOUND");
		// 結尾多一個斜線也是不存在的路徑（Spring 6 起不會自動對應）
		assertErrorFormat(mockMvc.perform(get("/api/v1/holdings/")).andExpect(status().isNotFound()), "NOT_FOUND");
		assertErrorFormat(mockMvc.perform(get("/api/v2/holdings")).andExpect(status().isNotFound()), "NOT_FOUND");
	}

	@Test
	void 方法不支援_回405_METHOD_NOT_ALLOWED_並附上Allow標頭() throws Exception {
		assertErrorFormat(mockMvc.perform(patch("/api/v1/holdings"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(header().string("Allow", containsString("GET"))), "METHOD_NOT_ALLOWED");
		assertErrorFormat(mockMvc.perform(patch("/api/v1/summary"))
				.andExpect(status().isMethodNotAllowed()), "METHOD_NOT_ALLOWED");
	}

	@Test
	void id不是數字或太大_回404_NOT_FOUND_不查資料庫() throws Exception {
		assertErrorFormat(mockMvc.perform(get("/api/v1/holdings/abc")).andExpect(status().isNotFound()), "NOT_FOUND");
		assertErrorFormat(mockMvc.perform(get("/api/v1/holdings/99999999999999999999"))
				.andExpect(status().isNotFound()), "NOT_FOUND");
		assertErrorFormat(mockMvc.perform(get("/api/v1/holdings/1.5")).andExpect(status().isNotFound()), "NOT_FOUND");
	}

	@Test
	void 非預期的例外_回500_INTERNAL_ERROR_不露出例外內容() throws Exception {
		given(holdingService.findAllForList()).willThrow(new IllegalStateException("資料庫連線失敗 secret-detail"));

		MvcResult result = mockMvc.perform(get("/api/v1/holdings"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error.message").value("伺服器發生錯誤，請稍後再試"))
				.andReturn();
		assertErrorFormat(mockMvc.perform(get("/api/v1/summary")), "INTERNAL_ERROR");
		assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
				.doesNotContain("secret-detail", "IllegalStateException");
	}

	@Test
	void 網頁的路徑不存在不受影響_不會變成API的JSON格式() throws Exception {
		MvcResult result = mockMvc.perform(get("/no-such-page"))
				.andExpect(status().isNotFound())
				.andReturn();
		assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("\"error\"");
	}
}
