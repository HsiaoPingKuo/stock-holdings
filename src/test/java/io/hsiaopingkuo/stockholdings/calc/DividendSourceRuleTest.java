package io.hsiaopingkuo.stockholdings.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.quote.DividendListResult;
import io.hsiaopingkuo.stockholdings.quote.DividendListRow;
import io.hsiaopingkuo.stockholdings.quote.TwseDividendDetail;
import io.hsiaopingkuo.stockholdings.quote.TwseDividendListClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 資料來源規則（第 6 題設計決定 7-C）：表的每一列至少一個例子。
 * 數字取自 2025-06～07 的實測列表（src/test/resources/dividends/）；實測沒有的情況才自己造。
 */
class DividendSourceRuleTest {

	// ===== 實測列表的列（欄位照官方原值） =====

	/** 上市 2330 2025-06-12 息：參考價 = 減除股利參考價。 */
	private static final DividendListRow TSMC = twse("2330", LocalDate.of(2025, 6, 12), DividendKind.CASH,
			"1065.00", "1060.49", "4.500020", "1060.49");
	/** 上市 2884 2025-07-22 權息。 */
	private static final DividendListRow ESUN = twse("2884", LocalDate.of(2025, 7, 22), DividendKind.CASH_AND_STOCK,
			"34.20", "32.67", "1.527222", "32.67");
	/** 上市 3311 閎暉 2025-06-19 權：減除股利參考價 = 前收（只有現金增資）。 */
	private static final DividendListRow HOLD_TECH = twse("3311", LocalDate.of(2025, 6, 19), DividendKind.STOCK,
			"38.00", "35.28", "2.720000", "38.00");
	/** 上市 6423 億而得-創 2025-06-12 權：參考價 = 減除股利參考價（沒有現金增資）。 */
	private static final DividendListRow YIERDE = twse("6423", LocalDate.of(2025, 6, 12), DividendKind.STOCK,
			"89.80", "88.01", "1.783844", "88.01");
	/** 上市 2528 皇普 2025-07-30 權：參考價 = 減除股利參考價，實際配股應是每千股 50 股。 */
	private static final DividendListRow HUANG_PU = twse("2528", LocalDate.of(2025, 7, 30), DividendKind.STOCK,
			"28.80", "27.42", "1.371429", "27.42");
	/** 上櫃 8069 元太 2025-07-24 除息。 */
	private static final DividendListRow EINK = new DividendListRow(Market.TPEX, LocalDate.of(2025, 7, 24), "8069",
			"元太", DividendKind.CASH, new BigDecimal("216.00"), new BigDecimal("211.01"), new BigDecimal("4.990139"),
			new BigDecimal("211.01"), new BigDecimal("4.99013930"), new BigDecimal("0.00000000"), null, null);

	private static DividendListRow twse(String code, LocalDate exDate, DividendKind kind, String prevClose,
			String reference, String rightsPlusDividend, String dividendReference) {
		return new DividendListRow(Market.TWSE, exDate, code, null, kind, decimal(prevClose), decimal(reference),
				decimal(rightsPlusDividend), decimal(dividendReference), null, null, code, exDate);
	}

	private static BigDecimal decimal(String value) {
		return value == null ? null : new BigDecimal(value);
	}

	private static void assertUnavailable(ResolvedDividend resolved) {
		assertThat(resolved.source()).isEqualTo(DividendSource.UNAVAILABLE);
		assertThat(resolved.cashPerShare()).isNull();
		assertThat(resolved.stockPerThousand()).isNull();
	}

	// ===== 第 1 列：上櫃 =====

	@Test
	void 上櫃_用列表的現金股利和配股_官方_8069是4點99013930() {
		ResolvedDividend resolved = DividendSourceRule.resolve(EINK, null);

		assertThat(resolved.source()).isEqualTo(DividendSource.OFFICIAL);
		// 用列表的 8 位小數，不是權值+息值的 4.990139
		assertThat(resolved.cashPerShare()).isEqualTo(new BigDecimal("4.99013930"));
		assertThat(resolved.stockPerThousand()).isEqualByComparingTo("0");
	}

	@Test
	void 上櫃_現金股利看不懂是null_無法取得() {
		DividendListRow row = new DividendListRow(Market.TPEX, EINK.exDate(), "8069", "元太", DividendKind.CASH,
				EINK.prevClose(), EINK.exRightReference(), EINK.rightsPlusDividend(), EINK.dividendReference(),
				null, BigDecimal.ZERO, null, null);

		assertUnavailable(DividendSourceRule.resolve(row, null));
	}

	// ===== 第 2 列：上市有明細 =====

	@Test
	void 上市有明細_權息用明細的數字_2884是現金1點2_每千股10股() {
		ResolvedDividend resolved = DividendSourceRule.resolve(ESUN,
				new TwseDividendDetail("2884", new BigDecimal("1.2"), new BigDecimal("10")));

		assertThat(resolved.source()).isEqualTo(DividendSource.OFFICIAL);
		assertThat(resolved.cashPerShare()).isEqualByComparingTo("1.2");
		assertThat(resolved.stockPerThousand()).isEqualByComparingTo("10");
	}

	@Test
	void 上市有明細_只除息也用明細_2330是4點50002() {
		ResolvedDividend resolved = DividendSourceRule.resolve(TSMC,
				new TwseDividendDetail("2330", new BigDecimal("4.50002"), BigDecimal.ZERO));

		assertThat(resolved.source()).isEqualTo(DividendSource.OFFICIAL);
		assertThat(resolved.cashPerShare()).isEqualTo(new BigDecimal("4.50002"));
	}

	// ===== 第 3 列：上市、沒有明細、息、參考價相等 =====

	@Test
	void 上市沒有明細_息_參考價等於減除股利參考價_權值加息值就是現金股利_官方() {
		ResolvedDividend resolved = DividendSourceRule.resolve(TSMC, null);

		assertThat(resolved.source()).isEqualTo(DividendSource.OFFICIAL);
		// 列表 4.500020 和明細 4.50002 數值相同
		assertThat(resolved.cashPerShare()).isEqualByComparingTo("4.50002");
		assertThat(resolved.stockPerThousand()).isEqualByComparingTo("0");
	}

	@Test
	void 參考價比較不看小數位數_1060點49和1060點490視為相等() {
		DividendListRow row = twse("2330", TSMC.exDate(), DividendKind.CASH, "1065.00", "1060.49", "4.500020",
				"1060.490");

		assertThat(DividendSourceRule.resolve(row, null).source()).isEqualTo(DividendSource.OFFICIAL);
	}

	// ===== 第 4 列：上市、沒有明細、權、減除股利參考價 = 前收 =====

	@Test
	void 上市沒有明細_權_減除股利參考價等於前收_只有現金增資_3311閎暉配股0_官方() {
		ResolvedDividend resolved = DividendSourceRule.resolve(HOLD_TECH, null);

		assertThat(resolved.source()).isEqualTo(DividendSource.OFFICIAL);
		assertThat(resolved.cashPerShare()).isEqualByComparingTo("0");
		assertThat(resolved.stockPerThousand()).isEqualByComparingTo("0");
	}

	// ===== 第 5 列：上市、沒有明細、權、參考價相等 =====

	@Test
	void 上市沒有明細_權_參考價等於減除股利參考價_用權值加息值估算_6423億而得() {
		ResolvedDividend resolved = DividendSourceRule.resolve(YIERDE, null);

		assertThat(resolved.source()).isEqualTo(DividendSource.ESTIMATED);
		assertThat(resolved.cashPerShare()).isEqualByComparingTo("0");
		// (89.80 ÷ (89.80 − 1.783844) − 1) × 1000 = 20.2672336656… → 8 位 HALF_UP
		assertThat(resolved.stockPerThousand()).isEqualTo(new BigDecimal("20.26723367"));
	}

	@Test
	void 上市沒有明細_權_估算接近實際配股_2528皇普約每千股50股() {
		ResolvedDividend resolved = DividendSourceRule.resolve(HUANG_PU, null);

		assertThat(resolved.source()).isEqualTo(DividendSource.ESTIMATED);
		// (28.80 ÷ 27.428571 − 1) × 1000 = 50.0000164062… ；1,000 股配 50 股，和實際一樣
		assertThat(resolved.stockPerThousand()).isEqualTo(new BigDecimal("50.00001641"));
	}

	// ===== 第 6 列：上市、沒有明細、權、參考價不相等 =====

	@Test
	void 上市沒有明細_權_參考價不等於減除股利參考價_用減除股利參考價估算() {
		// 實測沒有「有配股又有現金增資」的權，自己造一列：前收 100、除權息參考價 90.00、減除股利參考價 90.91
		DividendListRow row = twse("9901", LocalDate.of(2025, 7, 1), DividendKind.STOCK, "100.00", "90.00",
				"10.000000", "90.91");

		ResolvedDividend resolved = DividendSourceRule.resolve(row, null);

		assertThat(resolved.source()).isEqualTo(DividendSource.ESTIMATED);
		assertThat(resolved.cashPerShare()).isEqualByComparingTo("0");
		// (100 ÷ 90.91 − 1) × 1000 = 99.98900010999… → 99.98900011
		assertThat(resolved.stockPerThousand()).isEqualTo(new BigDecimal("99.98900011"));
	}

	// ===== 第 7 列：上市、沒有明細、權息 =====

	@Test
	void 上市沒有明細_權息_拆不出兩個數字_2884無法取得() {
		assertUnavailable(DividendSourceRule.resolve(ESUN, null));
	}

	// ===== 第 8 列：上市、沒有明細、息、參考價不相等 =====

	@Test
	void 上市沒有明細_息_參考價不等於減除股利參考價_無法取得() {
		// 三份實測檔（含 2024 整年）都沒有這種例子，自己造一列：息又有現金增資
		DividendListRow row = twse("9902", LocalDate.of(2025, 7, 1), DividendKind.CASH, "50.00", "47.50", "2.500000",
				"48.00");

		assertUnavailable(DividendSourceRule.resolve(row, null));
	}

	// ===== 資料不完整時不猜數字 =====

	@Test
	void 需要的價格是null_無法取得() {
		assertUnavailable(DividendSourceRule.resolve(
				twse("2330", TSMC.exDate(), DividendKind.CASH, "1065.00", null, "4.500020", "1060.49"), null));
		assertUnavailable(DividendSourceRule.resolve(
				twse("2330", TSMC.exDate(), DividendKind.CASH, "1065.00", "1060.49", null, "1060.49"), null));
		assertUnavailable(DividendSourceRule.resolve(
				twse("6423", YIERDE.exDate(), DividendKind.STOCK, null, "88.01", "1.783844", "88.01"), null));
		assertUnavailable(DividendSourceRule.resolve(
				twse("6423", YIERDE.exDate(), DividendKind.STOCK, "89.80", "88.01", null, "88.01"), null));
	}

	@Test
	void 分母小於等於0_無法取得() {
		// 權值+息值 ≥ 前收：前收 − 權值+息值 ≤ 0，不能除
		DividendListRow row = twse("9903", LocalDate.of(2025, 7, 1), DividendKind.STOCK, "10.00", "0.00", "10.000000",
				"0.00");

		assertUnavailable(DividendSourceRule.resolve(row, null));
	}

	// ===== 整份實測列表 =====

	@Test
	void 實測2025年6到7月_沒有明細時_息全部官方_權3官方3估算_權息全部無法取得() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://www.twse.com.tw");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://www.twse.com.tw/rwd/zh/exRight/TWT49U?startDate=20250601&endDate=20250731&response=json"))
				.andRespond(withSuccess(new ClassPathResource("dividends/twse-twt49u-20250601-20250731.json"),
				MediaType.APPLICATION_JSON));
		DividendListResult list = new TwseDividendListClient(builder.build(), JsonMapper.builder().build())
				.fetchRange(LocalDate.of(2025, 6, 1), LocalDate.of(2025, 7, 31));

		Map<DividendKind, List<DividendSource>> sourcesByKind = list.rows().stream()
				.collect(Collectors.groupingBy(DividendListRow::kind,
						Collectors.mapping(row -> DividendSourceRule.resolve(row, null).source(), Collectors.toList())));

		assertThat(sourcesByKind.get(DividendKind.CASH)).hasSize(638).containsOnly(DividendSource.OFFICIAL);
		assertThat(sourcesByKind.get(DividendKind.CASH_AND_STOCK)).hasSize(29).containsOnly(DividendSource.UNAVAILABLE);
		Map<String, DividendSource> stockSources = list.rows().stream()
				.filter(row -> row.kind() == DividendKind.STOCK)
				.collect(Collectors.toMap(DividendListRow::code, row -> DividendSourceRule.resolve(row, null).source()));
		assertThat(stockSources).containsOnly(
				Map.entry("3311", DividendSource.OFFICIAL),
				Map.entry("6541", DividendSource.OFFICIAL),
				Map.entry("1449", DividendSource.OFFICIAL),
				Map.entry("6423", DividendSource.ESTIMATED),
				Map.entry("6873", DividendSource.ESTIMATED),
				Map.entry("2528", DividendSource.ESTIMATED));
		assertThat(list.rows().stream().filter(row -> row.code().equals("6873")).findFirst()
				.map(row -> DividendSourceRule.resolve(row, null).stockPerThousand()))
				.contains(new BigDecimal("142.08472045"));
	}
}
