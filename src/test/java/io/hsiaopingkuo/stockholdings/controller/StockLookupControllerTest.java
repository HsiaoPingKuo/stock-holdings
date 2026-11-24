package io.hsiaopingkuo.stockholdings.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.hsiaopingkuo.stockholdings.config.SecurityConfig;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.exception.MarketAmbiguousException;
import io.hsiaopingkuo.stockholdings.exception.MarketUnavailableException;
import io.hsiaopingkuo.stockholdings.exception.StockCodeNotFoundException;
import io.hsiaopingkuo.stockholdings.quote.StockLookup;
import io.hsiaopingkuo.stockholdings.service.HoldingService;

/**
 * 新增表單的代號即時查詢（第 3 題）。第 6 題刪掉 1.0 網頁時，原本在 HoldingControllerTest 的這幾個測試原樣搬過來。
 */
@WebMvcTest(StockLookupController.class)
// @WebMvcTest 不會載入一般的 @Configuration；不 import 的話會套用 Spring Security 預設（全部要登入）
@Import(SecurityConfig.class)
class StockLookupControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private HoldingService holdingService;

	private static StockLookup found(Market market, String name) {
		return StockLookup.found(market, name, LocalDate.of(2026, 11, 23), new BigDecimal("100.00"));
	}

	@Test
	void 即時查詢_查到時回傳名稱與市場() throws Exception {
		given(holdingService.lookupStock("2330")).willReturn(found(Market.TWSE, "台積電"));

		mockMvc.perform(get("/holdings/lookup").param("code", "2330"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.code").value("2330"))
				.andExpect(jsonPath("$.name").value("台積電"))
				.andExpect(jsonPath("$.market").value("TWSE"))
				.andExpect(jsonPath("$.market_label").value("上市"));
	}

	@Test
	void 即時查詢_回傳正規化後的代號() throws Exception {
		given(holdingService.lookupStock("00631l")).willReturn(found(Market.TWSE, "元大台灣50正2"));

		mockMvc.perform(get("/holdings/lookup").param("code", " 00631l "))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("00631L"));
	}

	@Test
	void 即時查詢_名稱不明時輸出null而不是省略欄位() throws Exception {
		given(holdingService.lookupStock("6488")).willReturn(found(Market.TPEX, null));

		mockMvc.perform(get("/holdings/lookup").param("code", "6488"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"name\":null")))
				.andExpect(jsonPath("$.market_label").value("上櫃"));
	}

	@Test
	void 即時查詢_查不到回404與錯誤格式() throws Exception {
		given(holdingService.lookupStock("9999")).willThrow(new StockCodeNotFoundException("9999"));

		mockMvc.perform(get("/holdings/lookup").param("code", "9999"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("STOCK_NOT_FOUND"))
				.andExpect(jsonPath("$.error.message").value("查無此股票代號：9999（上市、上櫃都找不到）"));
	}

	@Test
	void 即時查詢_官方連不上回503與錯誤格式() throws Exception {
		given(holdingService.lookupStock("2330")).willThrow(new MarketUnavailableException());

		mockMvc.perform(get("/holdings/lookup").param("code", "2330"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("QUOTE_UNAVAILABLE"))
				.andExpect(jsonPath("$.error.message").value("暫時無法連線官方網站確認代號，請稍後再試"));
	}

	@Test
	void 即時查詢_兩個市場無法判斷回503與MARKET_UNDETERMINED_和AppAPI一致() throws Exception {
		given(holdingService.lookupStock("1234")).willThrow(new MarketAmbiguousException("1234"));

		mockMvc.perform(get("/holdings/lookup").param("code", "1234"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("MARKET_UNDETERMINED"))
				.andExpect(jsonPath("$.error.message").value("無法判斷 1234 是上市或上櫃，請聯絡管理者"));
	}

	@Test
	void 即時查詢_代號格式不對或沒給_回400且不查官方() throws Exception {
		mockMvc.perform(get("/holdings/lookup").param("code", "23-30"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_CODE"));
		mockMvc.perform(get("/holdings/lookup").param("code", "12345678901"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get("/holdings/lookup"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_CODE"));
		verify(holdingService, never()).lookupStock(anyString());
	}
}
