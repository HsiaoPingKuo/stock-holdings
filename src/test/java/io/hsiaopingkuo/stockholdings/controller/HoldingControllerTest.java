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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import io.hsiaopingkuo.stockholdings.calc.PortfolioSummary;
import io.hsiaopingkuo.stockholdings.calc.SummaryCalculator;
import io.hsiaopingkuo.stockholdings.dto.HoldingListItem;
import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.exception.DuplicateCodeException;
import io.hsiaopingkuo.stockholdings.exception.HoldingNotFoundException;
import io.hsiaopingkuo.stockholdings.exception.MarketAmbiguousException;
import io.hsiaopingkuo.stockholdings.exception.MarketUnavailableException;
import io.hsiaopingkuo.stockholdings.exception.StockCodeNotFoundException;
import io.hsiaopingkuo.stockholdings.quote.StockLookup;
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
		return holding(id, code, null, null, lots, buyPrice);
	}

	private static Holding holding(long id, String code, String name, Market market, int lots, String buyPrice) {
		Holding h = new Holding(code, name, market, lots, new BigDecimal(buyPrice), LocalDateTime.of(2026, 11, 24, 14, 0));
		// id 平常由資料庫產生，測試裡直接塞值
		ReflectionTestUtils.setField(h, "id", id);
		return h;
	}

	private static final LocalDate NOV_23 = LocalDate.of(2026, 11, 23);
	private static final LocalDate NOV_24 = LocalDate.of(2026, 11, 24);

	/** 清單的一列；收盤價、兩個日期都可以是 null（沒有行情）。 */
	private static HoldingListItem row(long id, String code, String name, Market market, int lots, String buyPrice,
			String closePrice, LocalDate closeDate, LocalDate latestTradeDate) {
		return new HoldingListItem(id, code, name, market, lots, new BigDecimal(buyPrice),
				closePrice == null ? null : new BigDecimal(closePrice), closeDate, latestTradeDate);
	}

	private static HoldingListItem rowWithoutQuote(long id, String code, String name, Market market, int lots,
			String buyPrice) {
		return row(id, code, name, market, lots, buyPrice, null, null, null);
	}

	@Test
	void 清單顯示收盤價與日期() throws Exception {
		given(holdingService.findAllForList()).willReturn(List.of(
				row(1, "2330", "台積電", Market.TWSE, 1, "1000.00", "2030.00", NOV_23, NOV_23)));

		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("收盤價日期")))
				.andExpect(content().string(containsString("2,030.00")))
				.andExpect(content().string(containsString("2026-11-23")))
				// 比對完整的格子和提示句：HTML 註解裡也有「未取得」這幾個字
				.andExpect(content().string(not(containsString("無成交，顯示"))))
				.andExpect(content().string(not(containsString("<td>未取得</td>"))));
	}

	@Test
	void 最近交易日沒有成交_顯示前一個有成交日的收盤價並提示() throws Exception {
		given(holdingService.findAllForList()).willReturn(List.of(
				row(1, "6488", "環球晶", Market.TPEX, 1, "450.00", "460.50", NOV_23, NOV_24)));

		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("460.50")))
				.andExpect(content().string(containsString("2026-11-24 無成交，顯示 2026-11-23 收盤價")));
	}

	@Test
	void 沒有行情或從沒成交過_收盤價顯示未取得_頁面正常() throws Exception {
		given(holdingService.findAllForList()).willReturn(List.of(
				rowWithoutQuote(1, "2330", "台積電", Market.TWSE, 1, "1000.00"),
				// 有行情但從沒成交過：收盤價是 null
				row(2, "1234", "測試股", Market.TWSE, 1, "10.00", null, null, NOV_24)));

		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("<td>未取得</td>")))
				.andExpect(content().string(not(containsString("無成交，顯示"))));
	}

	// ===== 第 4 題：盈虧、收益率、總計 =====

	/** 題目的例子：2330 賺錢、0050 虧錢（張數 2）。 */
	private static HoldingListItem tsmcGain() {
		return row(1, "2330", "台積電", Market.TWSE, 1, "1000.00", "1100.00", NOV_23, NOV_23);
	}

	private static HoldingListItem etfLoss() {
		return row(2, "0050", "元大台灣50", Market.TWSE, 2, "150.00", "145.50", NOV_23, NOV_23);
	}

	/** 沒有行情：成本 460,500 + 手續費 656 = 461,156。 */
	private static HoldingListItem gwNoQuote() {
		return rowWithoutQuote(3, "6488", "環球晶", Market.TPEX, 1, "460.50");
	}

	/** 和 controller 相同的算法，用來確認頁面數字和計算類別一致。 */
	private static PortfolioSummary summaryOf(HoldingListItem... rows) {
		return SummaryCalculator.summarize(List.of(rows).stream().map(HoldingListItem::profit).toList());
	}

	@Test
	void 每筆盈虧為正是紅色_為負是綠色_收益率跟著盈虧的顏色() throws Exception {
		given(holdingService.findAllForList()).willReturn(List.of(tsmcGain(), etfLoss()));

		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("<th>盈虧</th><th>收益率</th>")))
				.andExpect(content().string(containsString("<td class=\"num profit gain\">93,708</td>")))
				.andExpect(content().string(containsString("<td class=\"num rate gain\">9.36%</td>")))
				.andExpect(content().string(containsString("<td class=\"num profit loss\">-10,132</td>")))
				.andExpect(content().string(containsString("<td class=\"num rate loss\">-3.37%</td>")));
	}

	@Test
	void 沒有收盤價時盈虧與收益率顯示破折號且不上色() throws Exception {
		given(holdingService.findAllForList()).willReturn(List.of(gwNoQuote()));

		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("<td>未取得</td>")))
				.andExpect(content().string(containsString("<td class=\"num profit\">—</td>")))
				.andExpect(content().string(containsString("<td class=\"num rate\">—</td>")));
	}

	@Test
	void 收盤價是0時當成未取得_清單頁不會整頁錯誤() throws Exception {
		// 外部回傳 0.00 時，計算類別會拒絕（價格必須大於 0），清單要當成沒有收盤價，不能 500
		given(holdingService.findAllForList()).willReturn(List.of(
				row(1, "2330", "台積電", Market.TWSE, 1, "1000.00", "0.00", NOV_23, NOV_23)));

		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("<td>未取得</td>")))
				.andExpect(content().string(containsString("<td class=\"num profit\">—</td>")));
	}

	@Test
	void 總計顯示在頁面上_數字與計算類別一致() throws Exception {
		given(holdingService.findAllForList()).willReturn(List.of(tsmcGain(), etfLoss()));

		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				// 交給畫面的總計，就是計算類別用同樣幾筆算出來的結果
				.andExpect(model().attribute("summary", summaryOf(tsmcGain(), etfLoss())))
				.andExpect(content().string(containsString("<td id=\"summary-total-cost\" class=\"num\">1,301,852</td>")))
				.andExpect(content().string(containsString("<td id=\"summary-market-value\" class=\"num\">1,391,000</td>")))
				.andExpect(content().string(containsString("<td id=\"summary-profit\" class=\"num gain\">83,576</td>")))
				.andExpect(content().string(containsString("<td id=\"summary-return-rate\" class=\"num gain\">6.42%</td>")))
				.andExpect(content().string(not(containsString("summary-note"))));
	}

	@Test
	void 總計虧損時是綠色() throws Exception {
		given(holdingService.findAllForList()).willReturn(List.of(etfLoss()));

		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("<td id=\"summary-profit\" class=\"num loss\">-10,132</td>")))
				.andExpect(content().string(containsString("<td id=\"summary-return-rate\" class=\"num loss\">-3.37%</td>")));
	}

	@Test
	void 部分持股沒有收盤價_總計顯示數字並提示筆數() throws Exception {
		given(holdingService.findAllForList()).willReturn(List.of(tsmcGain(), gwNoQuote()));

		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				.andExpect(model().attribute("summary", summaryOf(tsmcGain(), gwNoQuote())))
				// 總成本包含沒有收盤價的那筆：1,001,425 + 461,156
				.andExpect(content().string(containsString("<td id=\"summary-total-cost\" class=\"num\">1,462,581</td>")))
				.andExpect(content().string(containsString("<td id=\"summary-market-value\" class=\"num\">1,100,000</td>")))
				.andExpect(content().string(containsString("<td id=\"summary-profit\" class=\"num gain\">93,708</td>")))
				// 93,708 ÷ 1,462,581 × 100 = 6.407…
				.andExpect(content().string(containsString("<td id=\"summary-return-rate\" class=\"num gain\">6.41%</td>")))
				.andExpect(content().string(containsString("1 筆未取得收盤價，不計入市值與盈虧")))
				.andExpect(content().string(not(containsString("所有持股都還沒取得收盤價"))));
	}

	@Test
	void 全部持股都沒有收盤價_總計的市值盈虧收益率顯示破折號_總成本照常顯示() throws Exception {
		given(holdingService.findAllForList()).willReturn(List.of(gwNoQuote()));

		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("<td id=\"summary-total-cost\" class=\"num\">461,156</td>")))
				.andExpect(content().string(containsString("<td id=\"summary-market-value\" class=\"num\">—</td>")))
				// 不上色：不是「沒賺沒賠」，是還算不出來
				.andExpect(content().string(containsString("<td id=\"summary-profit\" class=\"num\">—</td>")))
				.andExpect(content().string(containsString("<td id=\"summary-return-rate\" class=\"num\">—</td>")))
				.andExpect(content().string(containsString("所有持股都還沒取得收盤價")))
				.andExpect(content().string(not(containsString("筆未取得收盤價，不計入市值與盈虧"))));
	}

	@Test
	void 沒有任何持股_總計顯示0且不上色() throws Exception {
		given(holdingService.findAllForList()).willReturn(List.of());

		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("<td id=\"summary-total-cost\" class=\"num\">0</td>")))
				.andExpect(content().string(containsString("<td id=\"summary-market-value\" class=\"num\">0</td>")))
				.andExpect(content().string(containsString("<td id=\"summary-profit\" class=\"num\">0</td>")))
				.andExpect(content().string(containsString("<td id=\"summary-return-rate\" class=\"num\">0.00%</td>")))
				.andExpect(content().string(not(containsString("summary-note"))));
	}

	@Test
	void 清單顯示名稱與市場_舊資料沒有時顯示破折號() throws Exception {
		given(holdingService.findAllForList()).willReturn(List.of(
				rowWithoutQuote(1, "2330", "台積電", Market.TWSE, 1, "1000.00"),
				rowWithoutQuote(2, "6488", "環球晶", Market.TPEX, 1, "460.50"),
				rowWithoutQuote(3, "0050", null, null, 1, "150.00")));

		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("<td>台積電</td>")))
				.andExpect(content().string(containsString("<td>上市</td>")))
				.andExpect(content().string(containsString("<td>環球晶</td>")))
				.andExpect(content().string(containsString("<td>上櫃</td>")))
				.andExpect(content().string(containsString("<td>—</td>")));
	}

	@Test
	void 查無此代號_表單顯示錯誤且不導頁() throws Exception {
		given(holdingService.create(anyString(), anyInt(), any())).willThrow(new StockCodeNotFoundException("9999"));

		mockMvc.perform(post("/holdings").param("code", "9999").param("lots", "1").param("buyPrice", "1000"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("查無此股票代號：9999（上市、上櫃都找不到）")));
	}

	@Test
	void 官方連不上_表單顯示稍後再試且保留輸入內容() throws Exception {
		given(holdingService.create(anyString(), anyInt(), any())).willThrow(new MarketUnavailableException());

		mockMvc.perform(post("/holdings").param("code", "2330").param("lots", "3").param("buyPrice", "1000"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("暫時無法連線官方網站確認代號，請稍後再試")))
				// 使用者不用重打：剛才輸入的值還在表單上
				.andExpect(content().string(containsString("value=\"2330\"")))
				.andExpect(content().string(containsString("value=\"3\"")));
	}

	@Test
	void 兩個市場無法判斷_表單顯示請聯絡管理者且不導頁() throws Exception {
		given(holdingService.create(anyString(), anyInt(), any())).willThrow(new MarketAmbiguousException("1234"));

		mockMvc.perform(post("/holdings").param("code", "1234").param("lots", "1").param("buyPrice", "10"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("無法判斷 1234 是上市或上櫃，請聯絡管理者")));
	}

	// ===== 輸入代號時的即時提示：GET /holdings/lookup =====

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

	@Test
	void 新增表單有即時提示的位置和查詢網址() throws Exception {
		given(holdingService.findAllForList()).willReturn(List.of());

		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("id=\"code-hint\"")))
				.andExpect(content().string(containsString("data-lookup-url=\"/holdings/lookup\"")));
	}

	@Test
	void 修改頁顯示名稱與市場() throws Exception {
		given(holdingService.findById(1L)).willReturn(holding(1, "6488", "環球晶", Market.TPEX, 1, "460.50"));

		mockMvc.perform(get("/holdings/1/edit"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("<span id=\"name\">環球晶</span>")))
				.andExpect(content().string(containsString("<span id=\"market\">上櫃</span>")));
	}

	@Test
	void 清單頁顯示所有持股() throws Exception {
		given(holdingService.findAllForList()).willReturn(List.of(
				rowWithoutQuote(1, "0050", null, null, 2, "150.00"),
				rowWithoutQuote(2, "2330", null, null, 1, "1000.00")));

		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("0050")))
				.andExpect(content().string(containsString("1,000.00")))
				.andExpect(content().string(containsString("/holdings/2/edit")));
	}

	@Test
	void 沒有持股時顯示提示() throws Exception {
		given(holdingService.findAllForList()).willReturn(List.of());

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
