package io.hsiaopingkuo.stockholdings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.entity.Quote;
import io.hsiaopingkuo.stockholdings.quote.DailyQuote;
import io.hsiaopingkuo.stockholdings.quote.MarketResolver;
import io.hsiaopingkuo.stockholdings.quote.TpexOtcClient;
import io.hsiaopingkuo.stockholdings.quote.TpexTradingStockClient;
import io.hsiaopingkuo.stockholdings.quote.TwseMiIndexClient;
import io.hsiaopingkuo.stockholdings.quote.TwseStockDayClient;
import io.hsiaopingkuo.stockholdings.repository.HoldingRepository;
import io.hsiaopingkuo.stockholdings.repository.TradeRepository;
import io.hsiaopingkuo.stockholdings.support.TestHoldings;
import io.hsiaopingkuo.stockholdings.repository.QuoteRepository;

/**
 * 題目要求「不要每次打開清單就向官方查一次」：啟動整個程式，把所有官方 client 換成假的，
 * 確認使用者操作時完全沒有呼叫官方。
 * 用獨立的記憶體資料庫，不和其他測試共用資料。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:no-official-call;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class NoOfficialCallTest {

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

	private void verifyNoOfficialCall() {
		verifyNoInteractions(twseStockDayClient, tpexTradingStockClient, twseMiIndexClient, tpexOtcClient,
				marketResolver);
	}

	@Test
	void 打開清單只讀資料庫_不呼叫任何官方client() throws Exception {
		LocalDateTime now = LocalDateTime.of(2026, 11, 24, 14, 0);
		// 第 6 題：張數、買進價記在交易，存成「持股＋一筆整張買進」
		TestHoldings.saveWithBuy(holdingRepository, tradeRepository, "2330", "台積電", Market.TWSE, 1, "1000.00", now);
		TestHoldings.saveWithBuy(holdingRepository, tradeRepository, "6488", "環球晶", Market.TPEX, 1, "450.00", now);
		quoteRepository.save(new Quote(
				DailyQuote.ofDay("2330", "台積電", Market.TWSE, LocalDate.of(2026, 11, 23), new BigDecimal("2030.00")), now));

		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("2,030.00")))
				// 6488 沒有行情
				.andExpect(content().string(containsString("<td>未取得</td>")));
		mockMvc.perform(get("/holdings")).andExpect(status().isOk());

		verifyNoOfficialCall();
	}

	@Test
	void App2的三支讀取API只讀資料庫_不呼叫任何官方client() throws Exception {
		LocalDateTime now = LocalDateTime.of(2026, 11, 24, 14, 0);
		Long tsmcId = TestHoldings.saveWithBuy(holdingRepository, tradeRepository, "2330", "台積電", Market.TWSE, 1,
				"1000.00", now).getId();
		TestHoldings.saveWithBuy(holdingRepository, tradeRepository, "6488", "環球晶", Market.TPEX, 1, "450.00", now);
		quoteRepository.save(new Quote(
				DailyQuote.ofDay("2330", "台積電", Market.TWSE, LocalDate.of(2026, 11, 23), new BigDecimal("2030.00")), now));

		mockMvc.perform(get("/api/v2/holdings"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].current_price").value(2030.00))
				// 6488 沒有行情：不會為了補收盤價去問官方，直接回 null
				.andExpect(jsonPath("$[1].current_price").value(nullValue()));
		mockMvc.perform(get("/api/v2/holdings/{id}", tsmcId)).andExpect(status().isOk());
		mockMvc.perform(get("/api/v2/summary"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.quote_missing").value(1));

		verifyNoOfficialCall();
	}

	@Test
	void 行情快取有這個代號_即時提示和新增都不呼叫任何官方client() throws Exception {
		quoteRepository.save(new Quote(
				DailyQuote.ofDay("6488", "環球晶", Market.TPEX, LocalDate.of(2026, 11, 23), new BigDecimal("460.50")),
				LocalDateTime.of(2026, 11, 23, 14, 0)));

		mockMvc.perform(get("/holdings/lookup").param("code", "6488"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("環球晶"))
				.andExpect(jsonPath("$.market").value("TPEX"));
		// 第 6 題：網頁改成 2.0 的新增交易表單
		mockMvc.perform(post("/holdings/trades").with(csrf()).param("code", "6488").param("side", "BUY")
				.param("date", "2026-11-23").param("shares", "1000").param("price", "450")
				.param("requestId", "no-official-call-1"))
				.andExpect(redirectedUrl("/holdings"));

		assertThat(holdingRepository.findAll()).singleElement()
				.satisfies(h -> assertThat(h.getMarket()).isEqualTo(Market.TPEX));
		verifyNoOfficialCall();
	}
}
