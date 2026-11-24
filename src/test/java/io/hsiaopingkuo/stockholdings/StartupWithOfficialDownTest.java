package io.hsiaopingkuo.stockholdings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.repository.HoldingRepository;
import io.hsiaopingkuo.stockholdings.repository.TradeRepository;
import io.hsiaopingkuo.stockholdings.support.TestHoldings;
import io.hsiaopingkuo.stockholdings.service.QuoteRefreshService;

/**
 * 「啟動不了就是零分」：打開背景更新，官方主機指到本機不存在的服務（localhost:9，連線立刻被拒絕，不連外網），
 * 確認伺服器照常啟動、啟動查詢在背景失敗也不影響，清單頁正常顯示「未取得」。
 * 用獨立的記憶體資料庫，不和其他測試共用資料。
 */
@SpringBootTest(properties = {
		"quote.refresh.enabled=true",
		"spring.datasource.url=jdbc:h2:mem:startup-official-down;DB_CLOSE_DELAY=-1" })
@AutoConfigureMockMvc
class StartupWithOfficialDownTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private QuoteRefreshService refreshService;

	@AfterEach
	void cleanUp() {
		holdingRepository.deleteAll();
	}

	@Test
	void 官方連不上_伺服器照常啟動_啟動查詢在背景失敗_清單顯示未取得() throws Exception {
		// 啟動輪在背景執行：批次連不上（FAILED）會改走逐檔，並記下「上一輪用逐檔」。
		// 等到兩個市場都跑完啟動輪，證明它真的在背景跑過、而且失敗沒有讓程式出錯
		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
			assertThat(refreshService.state(Market.TWSE).usedPerStock()).isTrue();
			assertThat(refreshService.state(Market.TPEX).usedPerStock()).isTrue();
		});

		// 第 6 題：張數、買進價記在交易，存成「持股＋一筆整張買進」
		TestHoldings.saveWithBuy(holdingRepository, tradeRepository, "2330", "台積電", Market.TWSE, 1, "1000.00",
				LocalDateTime.of(2026, 11, 24, 14, 0));

		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("<td>未取得</td>")));
	}
}
