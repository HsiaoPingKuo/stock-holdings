package io.hsiaopingkuo.stockholdings.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import io.hsiaopingkuo.stockholdings.calc.PortfolioSummary;
import io.hsiaopingkuo.stockholdings.calc.ProfitCalculator;
import io.hsiaopingkuo.stockholdings.calc.ProfitResult;
import io.hsiaopingkuo.stockholdings.calc.SummaryCalculator;

/**
 * 第 6 題最重要的相容測試：用 1.0 的資料庫升級後，/api/v1 的回應和升級前「整份 JSON」一模一樣。
 *
 * 做法：
 * 1. Spring 啟動前，先用 Flyway 只跑到 V3（= 1.0 的資料表），照 1.0 的寫法寫入持股和行情
 * 2. 啟動程式：Spring 的 Flyway 會接著跑 V4 之後的 migration（持股轉成推估的買進），等於「停掉 1.0、用同一個資料庫啟動 2.0」
 * 3. 呼叫 /api/v1，和「用 Part A 規則（ProfitCalculator、SummaryCalculator）從 1.0 的資料算出來的 JSON」逐字比對
 *
 * 預期值不經過任何 2.0 的程式：張數、買進價直接用寫進 1.0 資料表的值，盈虧用 Part A 的計算類別。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiV1UpgradeCompatibilityTest {

	// 獨立的記憶體資料庫：DB_CLOSE_DELAY=-1 讓步驟 1 寫入的資料留到 Spring 啟動後
	private static final String URL = "jdbc:h2:mem:v1-upgrade;DB_CLOSE_DELAY=-1";
	private static final LocalDate NOV_23 = LocalDate.of(2026, 11, 23);
	private static final LocalDate NOV_24 = LocalDate.of(2026, 11, 24);

	/** 1.0 時存的一筆持股和它的行情；name、market 是 null 代表第 2 題時存的舊資料。 */
	private record V1Row(String code, String name, String market, int lots, String buyPrice, String quoteName,
			String quoteMarket, String closePrice) {
	}

	// 依代號排序（清單的順序）
	private static final List<V1Row> V1_DATA = List.of(
			// 題目第 4 題的 ETF 例子：Part A 測試是 -10132、-3.37%
			new V1Row("0050", "元大台灣50", "TWSE", 2, "150.00", "元大台灣50", "TWSE", "145.50"),
			// 第 2 題時存的舊持股：沒有名稱和市場，用行情快取補
			new V1Row("1101", null, null, 1, "40.00", "台泥", "TWSE", "41.00"),
			// 題目第 4 題的例子：Part A 測試是 93708、9.36%
			new V1Row("2330", "台積電", "TWSE", 1, "1000.00", "台積電", "TWSE", "1100.00"),
			// 有行情、但從沒成交過：沒有收盤價
			new V1Row("6488", "環球晶", "TPEX", 1, "450.00", "環球晶", "TPEX", null));

	// 代號 → 1.0 時的 id（App 1.0 記住的 id，升級後不能變）
	private static final Map<String, Long> IDS = new LinkedHashMap<>();

	@DynamicPropertySource
	static void upgradeFromV1Database(DynamicPropertyRegistry registry) {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(URL, "sa", "");
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("3").load().migrate();

		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		Timestamp created = Timestamp.valueOf(LocalDateTime.of(2026, 11, 20, 10, 30));
		for (V1Row row : V1_DATA) {
			jdbc.update("INSERT INTO holding (code, name, market, lots, buy_price, created_at, updated_at) "
					+ "VALUES (?, ?, ?, ?, ?, ?, ?)",
					row.code(), row.name(), row.market(), row.lots(), new BigDecimal(row.buyPrice()), created, created);
			IDS.put(row.code(), jdbc.queryForObject("SELECT id FROM holding WHERE code = ?", Long.class, row.code()));
			// 沒有收盤價：close_price、close_date 都是 NULL，最近交易日照樣有
			jdbc.update("INSERT INTO quote (code, name, market, close_price, close_date, latest_trade_date, updated_at) "
					+ "VALUES (?, ?, ?, ?, ?, ?, ?)",
					row.code(), row.quoteName(), row.quoteMarket(),
					row.closePrice() == null ? null : new BigDecimal(row.closePrice()),
					row.closePrice() == null ? null : Date.valueOf(NOV_23),
					Date.valueOf(row.closePrice() == null ? NOV_24 : NOV_23), created);
		}
		registry.add("spring.datasource.url", () -> URL);
	}

	@Autowired
	private MockMvc mockMvc;

	/** Part A 的規則：用 1.0 的張數、買進價、收盤價算出盈虧（不經過交易重播）。 */
	private static ProfitResult partAProfit(V1Row row) {
		return ProfitCalculator.calculate(row.code(), row.lots() * 1000L, new BigDecimal(row.buyPrice()),
				row.closePrice() == null ? null : new BigDecimal(row.closePrice()));
	}

	/** 1.0 回應的持股物件（欄位順序照規格），用 Part A 的計算結果組出來。 */
	private static String expectedItem(V1Row row) {
		ProfitResult profit = partAProfit(row);
		boolean hasClose = row.closePrice() != null;
		return "{\"id\":" + IDS.get(row.code())
				+ ",\"code\":\"" + row.code() + "\""
				+ ",\"name\":\"" + (row.name() != null ? row.name() : row.quoteName()) + "\""
				+ ",\"market\":\"" + (row.market() != null ? row.market() : row.quoteMarket()) + "\""
				+ ",\"lots\":" + row.lots()
				+ ",\"buy_price\":" + row.buyPrice()
				+ ",\"current_price\":" + (hasClose ? row.closePrice() : "null")
				+ ",\"price_date\":" + (hasClose ? "\"" + NOV_23 + "\"" : "null")
				+ ",\"pnl\":" + (hasClose ? profit.profit().toBigIntegerExact() : "null")
				+ ",\"return_pct\":" + (hasClose ? profit.returnRate().toPlainString() : "null")
				+ "}";
	}

	private String body(String path) throws Exception {
		return mockMvc.perform(get(path))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	@Test
	void 清單_整份JSON和升級前一樣() throws Exception {
		List<String> items = new ArrayList<>();
		for (V1Row row : V1_DATA) {
			items.add(expectedItem(row));
		}

		String body = body("/api/v1/holdings");

		assertThat(body).isEqualTo("[" + String.join(",", items) + "]");
		// 再用 Part A 既有測試的數字錨定，確認預期值本身就是 Part A 的結果
		assertThat(body).contains("\"pnl\":93708,\"return_pct\":9.36", "\"pnl\":-10132,\"return_pct\":-3.37");
	}

	@Test
	void 單筆_每一檔都和升級前一樣() throws Exception {
		for (V1Row row : V1_DATA) {
			assertThat(body("/api/v1/holdings/" + IDS.get(row.code()))).as(row.code()).isEqualTo(expectedItem(row));
		}
	}

	@Test
	void 總計_整份JSON和升級前一樣() throws Exception {
		PortfolioSummary summary = SummaryCalculator.summarize(
				V1_DATA.stream().map(ApiV1UpgradeCompatibilityTest::partAProfit).toList());
		String expected = "{\"total_cost\":" + summary.totalCost().toBigIntegerExact()
				+ ",\"market_value\":" + summary.marketValue().toBigIntegerExact()
				+ ",\"pnl\":" + summary.profit().toBigIntegerExact()
				+ ",\"return_pct\":" + summary.returnRate().toPlainString()
				+ ",\"quote_missing\":" + summary.missingCloseCount()
				+ "}";

		assertThat(body("/api/v1/summary")).isEqualTo(expected);
		assertThat(summary.missingCloseCount()).isEqualTo(1);
	}
}
