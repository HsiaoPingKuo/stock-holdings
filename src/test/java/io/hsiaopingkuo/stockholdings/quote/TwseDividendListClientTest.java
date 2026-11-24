package io.hsiaopingkuo.stockholdings.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.hsiaopingkuo.stockholdings.calc.DividendKind;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.quote.DividendListResult.Status;
import tools.jackson.databind.json.JsonMapper;

/** 上市除權息列表 TWT49U：不連網，用實測存下來的 JSON（2026-11-25）模擬官方回應。 */
class TwseDividendListClientTest {

	private static final String BASE_URL = "https://www.twse.com.tw";
	private static final LocalDate JUN_1 = LocalDate.of(2025, 6, 1);
	private static final LocalDate JUL_31 = LocalDate.of(2025, 7, 31);
	private static final String URL_JUN_JUL = BASE_URL
			+ "/rwd/zh/exRight/TWT49U?startDate=20250601&endDate=20250731&response=json";
	private static final String FIELDS = """
			["資料日期","股票代號","股票名稱","除權息前收盤價","除權息參考價","權值+息值","權/息","漲停價格","跌停價格",\
			"開盤競價基準","減除股利參考價","詳細資料"]""";

	private MockRestServiceServer server;

	private TwseDividendListClient clientFor(String baseUrl) {
		RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
		server = MockRestServiceServer.bindTo(builder).build();
		return new TwseDividendListClient(builder.build(), JsonMapper.builder().build());
	}

	private DividendListResult respond(String body) {
		TwseDividendListClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_JUN_JUL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		return client.fetchRange(JUN_1, JUL_31);
	}

	private static DividendListRow rowOf(DividendListResult result, String code) {
		return result.rows().stream().filter(r -> r.code().equals(code)).findFirst().orElseThrow();
	}

	@Test
	void 實測資料_2025年6到7月共673列_2330每個欄位都正確() {
		TwseDividendListClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_JUN_JUL))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(new ClassPathResource("dividends/twse-twt49u-20250601-20250731.json"),
						MediaType.APPLICATION_JSON));

		DividendListResult result = client.fetchRange(JUN_1, JUL_31);

		assertThat(result.status()).isEqualTo(Status.OK);
		assertThat(result.isFailure()).isFalse();
		assertThat(result.rows()).hasSize(673);
		DividendListRow tsmc = rowOf(result, "2330");
		assertThat(tsmc.market()).isEqualTo(Market.TWSE);
		assertThat(tsmc.exDate()).isEqualTo(LocalDate.of(2025, 6, 12));
		assertThat(tsmc.name()).isEqualTo("台積電");
		assertThat(tsmc.kind()).isEqualTo(DividendKind.CASH);
		// 千分位 "1,065.00" 要去掉
		assertThat(tsmc.prevClose()).isEqualByComparingTo("1065.00");
		assertThat(tsmc.exRightReference()).isEqualByComparingTo("1060.49");
		assertThat(tsmc.rightsPlusDividend()).isEqualByComparingTo("4.500020");
		assertThat(tsmc.dividendReference()).isEqualByComparingTo("1060.49");
		// 上市列表沒有這兩個欄位
		assertThat(tsmc.listedCashPerShare()).isNull();
		assertThat(tsmc.listedStockPerThousand()).isNull();
		assertThat(tsmc.detailStockNo()).isEqualTo("2330");
		assertThat(tsmc.detailDate()).isEqualTo(LocalDate.of(2025, 6, 12));
		server.verify();
	}

	@Test
	void 實測資料_2884權息每個欄位都正確() {
		TwseDividendListClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_JUN_JUL))
				.andRespond(withSuccess(new ClassPathResource("dividends/twse-twt49u-20250601-20250731.json"),
						MediaType.APPLICATION_JSON));

		DividendListRow esun = rowOf(client.fetchRange(JUN_1, JUL_31), "2884");

		assertThat(esun.exDate()).isEqualTo(LocalDate.of(2025, 7, 22));
		assertThat(esun.name()).isEqualTo("玉山金");
		assertThat(esun.kind()).isEqualTo(DividendKind.CASH_AND_STOCK);
		assertThat(esun.prevClose()).isEqualByComparingTo("34.20");
		assertThat(esun.exRightReference()).isEqualByComparingTo("32.67");
		assertThat(esun.rightsPlusDividend()).isEqualByComparingTo("1.527222");
		assertThat(esun.dividendReference()).isEqualByComparingTo("32.67");
		assertThat(esun.detailStockNo()).isEqualTo("2884");
		assertThat(esun.detailDate()).isEqualTo(LocalDate.of(2025, 7, 22));
	}

	@Test
	void 實測資料_同一檔不同日期是兩筆事件_3311閎暉的權和息() {
		TwseDividendListClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_JUN_JUL))
				.andRespond(withSuccess(new ClassPathResource("dividends/twse-twt49u-20250601-20250731.json"),
						MediaType.APPLICATION_JSON));

		DividendListResult result = client.fetchRange(JUN_1, JUL_31);

		assertThat(result.rows()).filteredOn(r -> r.code().equals("3311"))
				.extracting(DividendListRow::exDate, DividendListRow::kind)
				.containsExactly(
						tuple(LocalDate.of(2025, 6, 19), DividendKind.STOCK),
						tuple(LocalDate.of(2025, 7, 3), DividendKind.CASH));
	}

	@Test
	void 實測資料_2021年1月最早範圍也能解析() {
		TwseDividendListClient client = clientFor(BASE_URL);
		server.expect(requestTo(BASE_URL + "/rwd/zh/exRight/TWT49U?startDate=20210101&endDate=20210131&response=json"))
				.andRespond(withSuccess(new ClassPathResource("dividends/twse-twt49u-20210101-20210131.json"),
						MediaType.APPLICATION_JSON));

		DividendListResult result = client.fetchRange(LocalDate.of(2021, 1, 1), LocalDate.of(2021, 1, 31));

		assertThat(result.status()).isEqualTo(Status.OK);
		assertThat(result.rows()).hasSize(15);
		DividendListRow first = result.rows().get(0);
		assertThat(first.code()).isEqualTo("4108");
		assertThat(first.exDate()).isEqualTo(LocalDate.of(2021, 1, 5));
		assertThat(first.kind()).isEqualTo(DividendKind.STOCK);
	}

	@Test
	void 實測資料_沒有資料時stat是很抱歉_NO_DATA不是失敗() {
		TwseDividendListClient client = clientFor(BASE_URL);
		server.expect(requestTo(BASE_URL + "/rwd/zh/exRight/TWT49U?startDate=20250127&endDate=20250131&response=json"))
				.andRespond(withSuccess(new ClassPathResource("dividends/twse-twt49u-20250127-20250131-no-data.json"),
						MediaType.APPLICATION_JSON));

		DividendListResult result = client.fetchRange(LocalDate.of(2025, 1, 27), LocalDate.of(2025, 1, 31));

		assertThat(result.status()).isEqualTo(Status.NO_DATA);
		assertThat(result.isFailure()).isFalse();
		assertThat(result.rows()).isEmpty();
	}

	@Test
	void stat是OK但data是空陣列_NO_DATA() {
		assertThat(respond("{\"stat\":\"OK\",\"fields\":" + FIELDS + ",\"data\":[]}").status())
				.isEqualTo(Status.NO_DATA);
	}

	@Test
	void 不認得的stat_FAILED_不能當成沒有資料() {
		DividendListResult result = respond("{\"stat\":\"查詢日期大於今日，請重新查詢!\"}");

		assertThat(result.status()).isEqualTo(Status.FAILED);
		assertThat(result.isFailure()).isTrue();
	}

	@Test
	void stat是OK但沒有data_FAILED() {
		assertThat(respond("{\"stat\":\"OK\",\"fields\":" + FIELDS + "}").status()).isEqualTo(Status.FAILED);
	}

	@Test
	void 缺少減除股利參考價欄位_FAILED() {
		String body = """
				{"stat":"OK","fields":["資料日期","股票代號","股票名稱","除權息前收盤價","除權息參考價","權值+息值","權/息","詳細資料"],
				 "data":[["114年06月12日","2330","台積電","1,065.00","1,060.49","4.500020","息","2330,20250612"]]}
				""";

		assertThat(respond(body).status()).isEqualTo(Status.FAILED);
	}

	@Test
	void 欄位順序調換_照名稱找到正確的欄位() {
		String body = """
				{"stat":"OK",
				 "fields":["詳細資料","權/息","減除股利參考價","權值+息值","除權息參考價","除權息前收盤價","股票名稱","股票代號","資料日期"],
				 "data":[["2330,20250612","息","1,060.49","4.500020","1,060.49","1,065.00","台積電","2330","114年06月12日"]]}
				""";

		DividendListRow row = respond(body).rows().get(0);

		assertThat(row.code()).isEqualTo("2330");
		assertThat(row.name()).isEqualTo("台積電");
		assertThat(row.exDate()).isEqualTo(LocalDate.of(2025, 6, 12));
		assertThat(row.kind()).isEqualTo(DividendKind.CASH);
		assertThat(row.prevClose()).isEqualByComparingTo("1065.00");
		assertThat(row.rightsPlusDividend()).isEqualByComparingTo("4.500020");
		assertThat(row.dividendReference()).isEqualByComparingTo("1060.49");
	}

	@Test
	void 某一列日期看不懂_整份FAILED_不是略過那一列() {
		String body = "{\"stat\":\"OK\",\"fields\":" + FIELDS + ",\"data\":["
				+ "[\"114年06月12日\",\"2330\",\"台積電\",\"1,065.00\",\"1,060.49\",\"4.500020\",\"息\",\"1,165.00\",\"955.00\",\"1,060.00\",\"1,060.49\",\"2330,20250612\"],"
				+ "[\"114/07/22\",\"2884\",\"玉山金\",\"34.20\",\"32.67\",\"1.527222\",\"權息\",\"35.90\",\"29.45\",\"32.65\",\"32.67\",\"2884,20250722\"]]}";

		assertThat(respond(body).status()).isEqualTo(Status.FAILED);
	}

	@Test
	void 某一列種類看不懂_整份FAILED() {
		String body = "{\"stat\":\"OK\",\"fields\":" + FIELDS + ",\"data\":["
				+ "[\"114年06月12日\",\"2330\",\"台積電\",\"1,065.00\",\"1,060.49\",\"4.500020\",\"減資\",\"1,165.00\",\"955.00\",\"1,060.00\",\"1,060.49\",\"2330,20250612\"]]}";

		assertThat(respond(body).status()).isEqualTo(Status.FAILED);
	}

	@Test
	void 某一列價格是減號_那一列保留_該欄是null() {
		String body = "{\"stat\":\"OK\",\"fields\":" + FIELDS + ",\"data\":["
				+ "[\"114年06月12日\",\"2330\",\"台積電\",\"--\",\"1,060.49\",\"4.500020\",\"息\",\"1,165.00\",\"955.00\",\"1,060.00\",\"1,060.49\",\"\"]]}";

		DividendListResult result = respond(body);

		assertThat(result.status()).isEqualTo(Status.OK);
		DividendListRow row = result.rows().get(0);
		assertThat(row.prevClose()).isNull();
		assertThat(row.exRightReference()).isEqualByComparingTo("1060.49");
		// 詳細資料是空的：沒辦法查明細，但列表本身照樣可用
		assertThat(row.detailStockNo()).isNull();
		assertThat(row.detailDate()).isNull();
	}

	@Test
	void 回傳HTML或403_BLOCKED_也算失敗() {
		TwseDividendListClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_JUN_JUL)).andRespond(withSuccess("<html>請稍後再試</html>", MediaType.TEXT_HTML));
		server.expect(requestTo(URL_JUN_JUL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

		DividendListResult html = client.fetchRange(JUN_1, JUL_31);
		DividendListResult forbidden = client.fetchRange(JUN_1, JUL_31);

		assertThat(html.status()).isEqualTo(Status.BLOCKED);
		assertThat(html.isFailure()).isTrue();
		assertThat(forbidden.status()).isEqualTo(Status.BLOCKED);
	}

	@Test
	void HTTP500_404_JSON壞掉_FAILED() {
		TwseDividendListClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_JUN_JUL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
		server.expect(requestTo(URL_JUN_JUL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
		server.expect(requestTo(URL_JUN_JUL)).andRespond(withSuccess("{\"stat\":\"OK\",\"data\":[", MediaType.APPLICATION_JSON));

		assertThat(client.fetchRange(JUN_1, JUL_31).status()).isEqualTo(Status.FAILED);
		assertThat(client.fetchRange(JUN_1, JUL_31).status()).isEqualTo(Status.FAILED);
		assertThat(client.fetchRange(JUN_1, JUL_31).status()).isEqualTo(Status.FAILED);
	}

	@Test
	void 換成主考官的主機_路徑和參數不變() {
		TwseDividendListClient client = clientFor("http://test-server:8081");
		server.expect(requestTo("http://test-server:8081/rwd/zh/exRight/TWT49U?startDate=20250601&endDate=20250731&response=json"))
				.andRespond(withSuccess("{\"stat\":\"很抱歉，沒有符合條件的資料!\"}", MediaType.APPLICATION_JSON));

		assertThat(client.fetchRange(JUN_1, JUL_31).status()).isEqualTo(Status.NO_DATA);
		server.verify();
	}
}
