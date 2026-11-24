package io.hsiaopingkuo.stockholdings.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.exception.DuplicateCodeException;
import io.hsiaopingkuo.stockholdings.exception.HoldingNotFoundException;
import io.hsiaopingkuo.stockholdings.service.HoldingService;

/**
 * 只測網頁這一層：表單檢查、錯誤訊息顯示在表單上、成功後導回清單。
 * Service 用假的（MockitoBean），業務規則另外在 HoldingServiceTest 測。
 */
@WebMvcTest(HoldingController.class)
class HoldingControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private HoldingService holdingService;

	private static Holding holding(long id, String code, int lots, String buyPrice) {
		Holding h = new Holding(code, lots, new BigDecimal(buyPrice), LocalDateTime.of(2026, 11, 24, 14, 0));
		// id 平常由資料庫產生，測試裡直接塞值
		ReflectionTestUtils.setField(h, "id", id);
		return h;
	}

	@Test
	void 清單頁顯示所有持股() throws Exception {
		given(holdingService.findAll()).willReturn(List.of(
				holding(1, "0050", 2, "150.00"),
				holding(2, "2330", 1, "1000.00")));

		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("0050")))
				.andExpect(content().string(containsString("1,000.00")))
				.andExpect(content().string(containsString("/holdings/2/edit")));
	}

	@Test
	void 沒有持股時顯示提示() throws Exception {
		given(holdingService.findAll()).willReturn(List.of());

		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("目前沒有持股")));
	}

	@Test
	void 新增成功後導回清單() throws Exception {
		given(holdingService.create("2330", 1, new BigDecimal("1000"))).willReturn(holding(1, "2330", 1, "1000.00"));

		mockMvc.perform(post("/holdings").param("code", " 2330 ").param("lots", "1").param("buyPrice", "1000"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/holdings"))
				.andExpect(flash().attribute("message", "已新增 2330"));
		// 前後空白在 controller 就去掉了
		verify(holdingService).create("2330", 1, new BigDecimal("1000"));
	}

	@Test
	void 重複代號_表單顯示錯誤() throws Exception {
		given(holdingService.create(anyString(), anyInt(), any())).willThrow(new DuplicateCodeException("2330"));

		mockMvc.perform(post("/holdings").param("code", "2330").param("lots", "1").param("buyPrice", "1000"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("2330 已在持股中")));
	}

	@Test
	void 代號空白_顯示請輸入且不儲存() throws Exception {
		mockMvc.perform(post("/holdings").param("code", "   ").param("lots", "1").param("buyPrice", "1000"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("請輸入股票代號")));
		verify(holdingService, never()).create(anyString(), anyInt(), any());
	}

	@Test
	void 代號含英數字以外的字元_顯示錯誤() throws Exception {
		mockMvc.perform(post("/holdings").param("code", "23-30").param("lots", "1").param("buyPrice", "1000"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("股票代號只能是英文字母或數字")));
		verify(holdingService, never()).create(anyString(), anyInt(), any());
	}

	@Test
	void 代號超過10個字_顯示錯誤() throws Exception {
		mockMvc.perform(post("/holdings").param("code", "12345678901").param("lots", "1").param("buyPrice", "1000"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("股票代號最多 10 個字")));
	}

	@Test
	void 張數超出範圍_顯示錯誤() throws Exception {
		for (String lots : new String[] { "0", "1000" }) {
			mockMvc.perform(post("/holdings").param("code", "2330").param("lots", lots).param("buyPrice", "1000"))
					.andExpect(status().isOk())
					.andExpect(content().string(containsString("張數必須是 1～999 的整數")));
		}
		verify(holdingService, never()).create(anyString(), anyInt(), any());
	}

	@Test
	void 張數是小數_不會被截成整數而是顯示錯誤() throws Exception {
		mockMvc.perform(post("/holdings").param("code", "2330").param("lots", "1.5").param("buyPrice", "1000"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("張數必須是 1～999 的整數")));
		verify(holdingService, never()).create(anyString(), anyInt(), any());
	}

	@Test
	void 買進價不大於0_顯示錯誤() throws Exception {
		mockMvc.perform(post("/holdings").param("code", "2330").param("lots", "1").param("buyPrice", "0"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("買進價必須大於 0")));
	}

	@Test
	void 買進價超過兩位小數_顯示錯誤() throws Exception {
		mockMvc.perform(post("/holdings").param("code", "2330").param("lots", "1").param("buyPrice", "10.123"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("買進價最多兩位小數")));
		verify(holdingService, never()).create(anyString(), anyInt(), any());
	}

	@Test
	void 買進價不是數字_顯示錯誤() throws Exception {
		mockMvc.perform(post("/holdings").param("code", "2330").param("lots", "1").param("buyPrice", "abc"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("買進價必須是數字")));
	}

	@Test
	void 修改頁的代號只顯示不能輸入() throws Exception {
		given(holdingService.findById(1L)).willReturn(holding(1, "2330", 1, "1000.00"));

		mockMvc.perform(get("/holdings/1/edit"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("<strong id=\"code\">2330</strong>")))
				.andExpect(content().string(not(containsString("name=\"code\""))));
	}

	@Test
	void 修改成功後導回清單() throws Exception {
		given(holdingService.update(1L, 2, new BigDecimal("980.5"))).willReturn(holding(1, "2330", 2, "980.50"));

		mockMvc.perform(post("/holdings/1").param("lots", "2").param("buyPrice", "980.5"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/holdings"))
				.andExpect(flash().attribute("message", "已修改 2330"));
	}

	@Test
	void 修改時欄位錯誤_留在修改頁顯示錯誤() throws Exception {
		given(holdingService.findById(1L)).willReturn(holding(1, "2330", 1, "1000.00"));

		mockMvc.perform(post("/holdings/1").param("lots", "1000").param("buyPrice", "1000"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("修改持股")))
				.andExpect(content().string(containsString("張數必須是 1～999 的整數")));
		verify(holdingService, never()).update(any(), anyInt(), any());
	}

	@Test
	void 刪除後導回清單() throws Exception {
		mockMvc.perform(post("/holdings/1/delete"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/holdings"));
		verify(holdingService).delete(1L);
	}

	@Test
	void 刪除已不存在的持股_導回清單並顯示訊息而不是錯誤頁() throws Exception {
		willThrow(new HoldingNotFoundException(99L)).given(holdingService).delete(eq(99L));

		mockMvc.perform(post("/holdings/99/delete"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/holdings"))
				.andExpect(flash().attribute("errorMessage", "找不到這筆持股（id=99），可能已被刪除"));
	}
}
