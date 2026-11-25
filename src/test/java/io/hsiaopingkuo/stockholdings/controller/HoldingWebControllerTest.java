package io.hsiaopingkuo.stockholdings.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.StringUtils;

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
import io.hsiaopingkuo.stockholdings.repository.IdempotencyRecordRepository;
import io.hsiaopingkuo.stockholdings.repository.QuoteRepository;
import io.hsiaopingkuo.stockholdings.repository.TradeRepository;
import io.hsiaopingkuo.stockholdings.service.DividendEventProvider;

/**
 * 第 6 題 2.0 網頁：清單、明細的顯示，表單新增、修改、刪除交易（帶 CSRF、一次性編號），錯誤顯示在畫面上。
 * 真的資料庫和 Spring Security；官方 client、除權息提供者換成假的，測試不會連網。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:web-v2;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class HoldingWebControllerTest {

	// UTC 06:00 = 台北 14:00（週三）；頁面要顯示 14:00 才代表真的用台北時區
	private static final Instant FIXED = Instant.parse("2026-11-25T06:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 14, 0);
	private static final LocalDate NOV_02 = LocalDate.of(2026, 11, 2);
	private static final LocalDate NOV_10 = LocalDate.of(2026, 11, 10);
	private static final LocalDate NOV_16 = LocalDate.of(2026, 11, 16);
	private static final LocalDate NOV_18 = LocalDate.of(2026, 11, 18);
	private static final LocalDate NOV_20 = LocalDate.of(2026, 11, 20);
	private static final LocalDate NOV_23 = LocalDate.of(2026, 11, 23);

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

	private Holding holding(String code, String name) {
		return holdingRepository.saveAndFlush(new Holding(code, name, Market.TWSE, NOW));
	}

	private Trade trade(Holding holding, TradeSide side, LocalDate date, long shares, String price) {
		return trade(holding, side, date, shares, price, false);
	}

	private Trade trade(Holding holding, TradeSide side, LocalDate date, long shares, String price,
			boolean estimated) {
		return tradeRepository
				.saveAndFlush(new Trade(holding.getId(), side, date, shares, new BigDecimal(price), estimated, NOW));
	}

	private void quote(String code, String name, String closePrice) {
		quoteRepository.saveAndFlush(
				new Quote(DailyQuote.ofDay(code, name, Market.TWSE, NOV_23, new BigDecimal(closePrice)), NOW));
	}

	private void cashDividend(String code, LocalDate exDate, String perShare, DividendSource source) {
		given(dividendEventProvider.eventsByCode(any())).willReturn(Map.of(code, List.of(
				new DividendEventInput(exDate, DividendKind.CASH, new BigDecimal(perShare), BigDecimal.ZERO, source))));
	}

	private String html(MockHttpServletRequestBuilder request) throws Exception {
		return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
	}

	private Trade reload(Trade trade) {
		return tradeRepository.findById(trade.getId()).orElseThrow();
	}

	// ===== 清單 =====

	@Test
	void 首頁和holdings都是清單頁_頂端顯示標題與台北時間() throws Exception {
		for (String url : new String[] { "/", "/holdings" }) {
			String body = html(get(url));
			assertThat(body).contains("<h1>持股記帳</h1>", "2026-11-25 14:00:00",
					"data-epoch-millis=\"" + FIXED.toEpochMilli() + "\"", "setInterval(render, 1000)",
					// 是清單頁：有新增交易表單和總計
					"id=\"create-form\"", "id=\"summary\"");
		}
	}

	/**
	 * - 2330：11/02 買 2,000 股 @1,000、11/10 除息每股 5 元、11/20 賣 1,000 股 @1,100，收盤價 1,100（數字同 ApiV2ReadTest）
	 * - 2454：買 1,500 股 @500（含零股 500 股），沒有收盤價
	 * - 2317：買 1,000 @100 再全部賣出 @110（已賣光）
	 */
	@Test
	void 清單_每檔數字_零股提示_已賣光排在最後_總計和API一致() throws Exception {
		quote("2330", "台積電", "1100.00");
		Holding tsmc = holding("2330", "台積電");
		trade(tsmc, TradeSide.BUY, NOV_02, 2000, "1000.00");
		trade(tsmc, TradeSide.SELL, NOV_20, 1000, "1100.00");
		Holding mtk = holding("2454", "聯發科");
		trade(mtk, TradeSide.BUY, NOV_02, 1500, "500.00");
		Holding foxconn = holding("2317", "鴻海");
		trade(foxconn, TradeSide.BUY, NOV_02, 1000, "100.00");
		trade(foxconn, TradeSide.SELL, NOV_20, 1000, "110.00");
		cashDividend("2330", NOV_10, "5", DividendSource.OFFICIAL);

		String body = html(get("/holdings"));

		// 2330：平均成本 1,001.43、市值 1,100,000、未實現 93,708、已實現 93,708、股利 10,000、含息總報酬 197,416
		assertThat(body).contains("1,001.43", "1,100,000", "93,708", "10,000", "197,416");
		assertThat(body).contains("含零股 500 股・App 1.0 只顯示整張，請更新 App 2.0");
		// 還沒抓過除權息資料：股利資料更新中
		assertThat(body).contains("股利資料更新中");
		// 沒有收盤價
		assertThat(body).contains("未取得");
		// 每檔的操作只有「查看交易」
		assertThat(body).contains("href=\"/holdings/" + tsmc.getId() + "\">查看交易</a>").doesNotContain(">修改<");
		// 持有中依代號 2330、2454，最後才是「已賣光」的 2317（灰字）
		String table = body.substring(body.indexOf("id=\"holdings\""));
		// 比對小標題那一格（頁面的 HTML 註解裡也有「已賣光」三個字）
		String soldOutHeading = "<th colspan=\"13\">已賣光</th>";
		assertThat(table.indexOf(">2330<")).isLessThan(table.indexOf(">2454<"));
		assertThat(table.indexOf(">2454<")).isLessThan(table.indexOf(soldOutHeading));
		assertThat(table.indexOf(soldOutHeading)).isLessThan(table.indexOf(">2317<"));
		assertThat(table).contains("class=\"sold-out\"");
		// 總計同 /api/v2/summary：含息總報酬 = 已實現 (9,372 + 93,708) + 未實現 93,708 + 股利 10,000 = 206,788
		String apiSummary = mockMvc.perform(get("/api/v2/summary")).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
		assertThat(apiSummary).contains("\"total_return\":206788");
		assertThat(body).contains("id=\"summary-total-return\" class=\"num gain\">206,788<");
	}

	/**
	 * Part A 第 4 題的三個例子：每一檔的收益率、紅賺綠虧，以及總計收益率和 /api/v1/summary 的 return_pct 一致。
	 * 總計：未實現 93,708 − 10,132 + 429 = 84,005，總成本 1,001,425 + 300,427 + 10,020 = 1,311,872 → 6.40%
	 */
	@Test
	void 清單_收益率_PartA三個例子_總計和v1一致() throws Exception {
		quote("2330", "台積電", "1100.00");
		quote("0050", "元大台灣50", "145.50");
		quote("2363", "矽統", "10.50");
		Holding tsmc = holding("2330", "台積電");
		trade(tsmc, TradeSide.BUY, NOV_02, 1000, "1000.00");
		Holding etf = holding("0050", "元大台灣50");
		trade(etf, TradeSide.BUY, NOV_02, 2000, "150.00");
		Holding sis = holding("2363", "矽統");
		trade(sis, TradeSide.BUY, NOV_02, 1000, "10.00");
		given(dividendEventProvider.eventsByCode(any())).willReturn(Map.of());

		String body = html(get("/holdings"));

		// 紅色（gain）是賺、綠色（loss）是虧
		assertThat(body).contains("<th>收益率</th>");
		assertThat(body).contains("class=\"num gain\">9.36%<", "class=\"num loss\">-3.37%<",
				"class=\"num gain\">4.28%<");
		assertThat(body).contains("id=\"summary-return-rate\" class=\"num gain\">6.40%<");
		String v1Summary = mockMvc.perform(get("/api/v1/summary")).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
		assertThat(v1Summary).contains("\"return_pct\":6.40");

		// 明細頁上方的數字也有收益率
		String detail = html(get("/holdings/{id}", tsmc.getId()));
		assertThat(detail).contains("id=\"holding-return-rate\" class=\"num gain\">9.36%<");
	}

	@Test
	void 清單_收益率_沒有收盤價和已賣光顯示橫線() throws Exception {
		Holding mtk = holding("2454", "聯發科");
		trade(mtk, TradeSide.BUY, NOV_02, 1000, "500.00");
		given(dividendEventProvider.eventsByCode(any())).willReturn(Map.of());

		String detail = html(get("/holdings/{id}", mtk.getId()));
		assertThat(detail).contains("id=\"holding-return-rate\" class=\"num\">—<");

		Holding foxconn = holding("2317", "鴻海");
		trade(foxconn, TradeSide.BUY, NOV_02, 1000, "100.00");
		trade(foxconn, TradeSide.SELL, NOV_20, 1000, "110.00");
		String soldOut = html(get("/holdings/{id}", foxconn.getId()));
		assertThat(soldOut).contains("id=\"holding-return-rate\" class=\"num\">—<");
		// 還有股數的只有 2454、沒有收盤價：網頁總計顯示「—」（Part A 第 4 題的決定），/api/v1 照規格回 0
		assertThat(html(get("/holdings"))).contains("id=\"summary-return-rate\" class=\"num\">—<");
		String v1Summary = mockMvc.perform(get("/api/v1/summary")).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
		assertThat(v1Summary).contains("\"return_pct\":0.00");
	}

	@Test
	void 新增和修改的日期欄位_只能選2021年1月1日到今天() throws Exception {
		Holding tsmc = holding("2330", "台積電");
		Trade buy = trade(tsmc, TradeSide.BUY, NOV_02, 1000, "1000.00");
		given(dividendEventProvider.eventsByCode(any())).willReturn(Map.of());

		String list = html(get("/holdings"));
		String editing = html(get("/holdings/{id}", tsmc.getId()).param("edit", buy.getId().toString()));

		// 「今天」是台北時間的 11/25（固定時鐘 UTC 06:00）
		for (String body : new String[] { list, editing }) {
			assertThat(body).containsPattern("type=\"date\"[^>]*min=\"2021-01-01\" max=\"2026-11-25\"");
		}
	}

	// ===== 明細 =====

	@Test
	void 明細_推估標示_除權息來源_單次2萬元以上提示補充保費() throws Exception {
		Holding tsmc = holding("2330", "台積電");
		// 1.0 轉入的買進：日期是推估
		trade(tsmc, TradeSide.BUY, NOV_02, 2000, "1000.00", true);
		given(dividendEventProvider.eventsByCode(any())).willReturn(Map.of("2330", List.of(
				// 2,000 × 5 = 10,000：不到 2 萬，不提示
				new DividendEventInput(NOV_10, DividendKind.CASH, new BigDecimal("5"), BigDecimal.ZERO,
						DividendSource.OFFICIAL),
				// 2,000 × 12 = 24,000：提示補充保費
				new DividendEventInput(NOV_16, DividendKind.CASH, new BigDecimal("12"), BigDecimal.ZERO,
						DividendSource.ESTIMATED),
				new DividendEventInput(NOV_18, DividendKind.CASH_AND_STOCK, null, null, DividendSource.UNAVAILABLE))));

		String body = html(get("/holdings/{id}", tsmc.getId()));

		assertThat(body).contains("推估", "請按「修改」改成實際日期");
		assertThat(body).contains("除權息日當天或之後才買進的股票，領不到這次的股利");
		assertThat(body).contains(">官方<", ">估算<", ">無法取得<", "24,000");
		assertThat(StringUtils.countOccurrencesOf(body, "入帳時會先扣 2.11% 二代健保補充保費")).isEqualTo(1);
	}

	@Test
	void 明細_資料異常時標出是哪一筆交易() throws Exception {
		Holding tsmc = holding("2330", "台積電");
		trade(tsmc, TradeSide.BUY, NOV_02, 1000, "1000.00");
		// 手動改過資料庫：賣出比持有多
		Trade sell = trade(tsmc, TradeSide.SELL, NOV_20, 2000, "1100.00");
		given(dividendEventProvider.eventsByCode(any())).willReturn(Map.of());

		assertThat(html(get("/holdings"))).contains("資料異常");
		String body = html(get("/holdings/{id}", tsmc.getId()));
		assertThat(body).contains("<tr id=\"trade-" + sell.getId() + "\" class=\"marked\">");
		assertThat(body).contains("資料異常：這筆賣出的股數超過當時的持股，請修改或刪除");
	}

	// ===== 新增、修改、刪除 =====

	@Test
	void 表單新增交易_同一個一次性編號送兩次只新增一次() throws Exception {
		Holding tsmc = holding("2330", "台積電");
		trade(tsmc, TradeSide.BUY, NOV_02, 1000, "1000.00");
		given(dividendEventProvider.eventsByCode(any())).willReturn(Map.of());

		for (int i = 0; i < 2; i++) {
			mockMvc.perform(post("/holdings/trades").with(csrf())
					.param("code", "2330").param("side", "BUY").param("date", "2026-11-24")
					.param("shares", "500").param("price", "1050").param("requestId", "web-create-1"))
					.andExpect(redirectedUrl("/holdings"))
					.andExpect(flash().attribute("message", "已新增 2330 買進 500 股"));
		}
		assertThat(tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(tsmc.getId())).hasSize(2);
	}

	@Test
	void 表單修改交易_同一個一次性編號送兩次只修改一次() throws Exception {
		Holding tsmc = holding("2330", "台積電");
		Trade buy = trade(tsmc, TradeSide.BUY, NOV_02, 1000, "1000.00");
		long version = buy.getVersion();
		given(dividendEventProvider.eventsByCode(any())).willReturn(Map.of());

		for (int i = 0; i < 2; i++) {
			mockMvc.perform(post("/holdings/{id}/trades/{tradeId}", tsmc.getId(), buy.getId()).with(csrf())
					.param("date", "2026-11-03").param("shares", "1200").param("price", "990.5")
					.param("version", String.valueOf(version)).param("requestId", "web-update-1"))
					.andExpect(redirectedUrl("/holdings/" + tsmc.getId()))
					.andExpect(flash().attribute("message", "已修改 2026-11-03 的買進 1,200 股"));
		}
		Trade updated = reload(buy);
		assertThat(updated.getShares()).isEqualTo(1200);
		// 只改了一次：版本號只加 1（第二次照第一次的結果導頁，沒有再寫入）
		assertThat(updated.getVersion()).isEqualTo(version + 1);
	}

	@Test
	void 表單刪除交易_同一個一次性編號送兩次只刪除一次() throws Exception {
		Holding tsmc = holding("2330", "台積電");
		trade(tsmc, TradeSide.BUY, NOV_02, 1000, "1000.00");
		Trade second = trade(tsmc, TradeSide.BUY, NOV_10, 1000, "1010.00");
		given(dividendEventProvider.eventsByCode(any())).willReturn(Map.of());

		for (int i = 0; i < 2; i++) {
			mockMvc.perform(post("/holdings/{id}/trades/{tradeId}/delete", tsmc.getId(), second.getId()).with(csrf())
					.param("version", String.valueOf(second.getVersion())).param("requestId", "web-delete-1"))
					.andExpect(redirectedUrl("/holdings/" + tsmc.getId()))
					.andExpect(flash().attribute("message", "已刪除 2026-11-10 的買進 1,000 股"));
		}
		assertThat(tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(tsmc.getId())).hasSize(1);
	}

	@Test
	void 新增_週末日期_錯誤標在日期欄位_不儲存() throws Exception {
		Holding tsmc = holding("2330", "台積電");
		trade(tsmc, TradeSide.BUY, NOV_02, 1000, "1000.00");
		given(dividendEventProvider.eventsByCode(any())).willReturn(Map.of());

		String body = html(post("/holdings/trades").with(csrf())
				.param("code", "2330").param("side", "BUY").param("date", "2026-11-22")
				.param("shares", "500").param("price", "1050").param("requestId", "web-create-2"));

		assertThat(body).contains("2026-11-22 是週末，不是交易日，請確認日期");
		assertThat(tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(tsmc.getId())).hasSize(1);
	}

	// ===== 錯誤顯示 =====

	@Test
	void 修改後超賣_明細頁顯示完整訊息並標出超賣的那一筆() throws Exception {
		Holding tsmc = holding("2330", "台積電");
		Trade buy = trade(tsmc, TradeSide.BUY, NOV_02, 1000, "1000.00");
		Trade sell = trade(tsmc, TradeSide.SELL, NOV_20, 1000, "1100.00");
		given(dividendEventProvider.eventsByCode(any())).willReturn(Map.of());

		String body = html(post("/holdings/{id}/trades/{tradeId}", tsmc.getId(), buy.getId()).with(csrf())
				.param("date", "2026-11-02").param("shares", "500").param("price", "1000")
				.param("version", String.valueOf(buy.getVersion())).param("requestId", "web-update-2"));

		assertThat(body).contains("2026-11-20 的賣出 1,000 股不夠賣：當時只持有 500 股");
		assertThat(body).contains("<tr id=\"trade-" + sell.getId() + "\" class=\"marked\">");
		// 沒有寫入
		assertThat(reload(buy).getShares()).isEqualTo(1000);
	}

	@Test
	void 版本衝突_導回明細頁並顯示請重新整理() throws Exception {
		Holding tsmc = holding("2330", "台積電");
		Trade buy = trade(tsmc, TradeSide.BUY, NOV_02, 1000, "1000.00");
		given(dividendEventProvider.eventsByCode(any())).willReturn(Map.of());

		MvcResult result = mockMvc.perform(post("/holdings/{id}/trades/{tradeId}", tsmc.getId(), buy.getId())
				.with(csrf())
				.param("date", "2026-11-02").param("shares", "2000").param("price", "1000")
				// 畫面上看到的是舊的版本號：別的裝置已經改過
				.param("version", String.valueOf(buy.getVersion() + 5)).param("requestId", "web-update-3"))
				.andExpect(redirectedUrl("/holdings/" + tsmc.getId()))
				.andReturn();

		MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
		assertThat(html(get("/holdings/{id}", tsmc.getId()).session(session)))
				.contains("這筆交易已被其他裝置修改，請重新整理");
		assertThat(reload(buy).getShares()).isEqualTo(1000);
	}
}
