package io.hsiaopingkuo.stockholdings;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.entity.Quote;
import io.hsiaopingkuo.stockholdings.quote.DailyQuote;
import io.hsiaopingkuo.stockholdings.quote.MarketResolver;
import io.hsiaopingkuo.stockholdings.quote.TpexOtcClient;
import io.hsiaopingkuo.stockholdings.quote.TpexTradingStockClient;
import io.hsiaopingkuo.stockholdings.quote.TwseMiIndexClient;
import io.hsiaopingkuo.stockholdings.quote.TwseStockDayClient;
import io.hsiaopingkuo.stockholdings.repository.HoldingRepository;
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
		holdingRepository.save(new Holding("2330", "台積電", Market.TWSE, 1, new BigDecimal("1000.00"), now));
		holdingRepository.save(new Holding("6488", "環球晶", Market.TPEX, 1, new BigDecimal("450.00"), now));
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
}
