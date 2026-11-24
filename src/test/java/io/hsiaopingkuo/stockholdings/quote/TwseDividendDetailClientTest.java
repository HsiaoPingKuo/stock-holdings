package io.hsiaopingkuo.stockholdings.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.hsiaopingkuo.stockholdings.quote.DividendDetailResult.Status;
import tools.jackson.databind.json.JsonMapper;

/** 上市除權息明細 TWT49UDetail：不連網，用實測存下來的 JSON（2026-11-25）模擬官方回應。 */
class TwseDividendDetailClientTest {

	private static final String BASE_URL = "https://www.twse.com.tw";
	private static final LocalDate JUL_22 = LocalDate.of(2025, 7, 22);
	private static final String URL_2884 = BASE_URL + "/rwd/zh/exRight/TWT49UDetail?STK_NO=2884&T1=20250722&response=json";
	private static final String FIELDS = """
			["股票代號","股票名稱","(每股配發現金股利)除息","(增資配股) 除權","A. 按普通股股東持股比例每千股無償配股"]""";

	private MockRestServiceServer server;

	private TwseDividendDetailClient clientFor(String baseUrl) {
		RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
		server = MockRestServiceServer.bindTo(builder).build();
		return new TwseDividendDetailClient(builder.build(), JsonMapper.builder().build());
	}

	private DividendDetailResult respond2884(String body) {
		TwseDividendDetailClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_2884)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		return client.fetchDetail("2884", JUL_22);
	}

	@Test
	void 實測資料_2884權息_現金1點2元_每千股10股() {
		TwseDividendDetailClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_2884))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(new ClassPathResource("dividends/twse-twt49u-detail-2884-20250722.json"),
						MediaType.APPLICATION_JSON));

		DividendDetailResult result = client.fetchDetail("2884", JUL_22);

		assertThat(result.status()).isEqualTo(Status.FOUND);
		assertThat(result.isFailure()).isFalse();
		// 代號尾端的空白要去掉
		assertThat(result.detail().code()).isEqualTo("2884");
		assertThat(result.detail().cashPerShare()).isEqualByComparingTo("1.2");
		assertThat(result.detail().stockPerThousand()).isEqualByComparingTo("10");
		server.verify();
	}

	@Test
	void 實測資料_2330只除息_現金4點50002元_配股0() {
		TwseDividendDetailClient client = clientFor(BASE_URL);
		server.expect(requestTo(BASE_URL + "/rwd/zh/exRight/TWT49UDetail?STK_NO=2330&T1=20250612&response=json"))
				.andRespond(withSuccess(new ClassPathResource("dividends/twse-twt49u-detail-2330-20250612.json"),
						MediaType.APPLICATION_JSON));

		DividendDetailResult result = client.fetchDetail("2330", LocalDate.of(2025, 6, 12));

		assertThat(result.status()).isEqualTo(Status.FOUND);
		assertThat(result.detail().cashPerShare()).isEqualTo(new BigDecimal("4.50002"));
		assertThat(result.detail().stockPerThousand()).isEqualByComparingTo("0");
	}

	@Test
	void 實測資料_查不到時stat是無相關資料_NOT_FOUND不是失敗() {
		TwseDividendDetailClient client = clientFor(BASE_URL);
		server.expect(requestTo(BASE_URL + "/rwd/zh/exRight/TWT49UDetail?STK_NO=9999&T1=20250722&response=json"))
				.andRespond(withSuccess(new ClassPathResource("dividends/twse-twt49u-detail-9999-not-found.json"),
						MediaType.APPLICATION_JSON));

		DividendDetailResult result = client.fetchDetail("9999", JUL_22);

		assertThat(result.status()).isEqualTo(Status.NOT_FOUND);
		assertThat(result.isFailure()).isFalse();
		assertThat(result.detail()).isNull();
	}

	@Test
	void stat是ok但data是空陣列_NOT_FOUND() {
		assertThat(respond2884("{\"stat\":\"ok\",\"fields\":" + FIELDS + ",\"data\":[]}").status())
				.isEqualTo(Status.NOT_FOUND);
	}

	@Test
	void 配股欄位是空字串_當成0() {
		String body = "{\"stat\":\"ok\",\"fields\":" + FIELDS
				+ ",\"data\":[[\"2884  \",\"玉山金   \",\"1.2 元／股\",\"\",\"\"]]}";

		DividendDetailResult result = respond2884(body);

		assertThat(result.status()).isEqualTo(Status.FOUND);
		assertThat(result.detail().stockPerThousand()).isEqualByComparingTo("0");
	}

	@Test
	void 回傳的代號和查詢的不同_FAILED() {
		String body = "{\"stat\":\"ok\",\"fields\":" + FIELDS
				+ ",\"data\":[[\"2330  \",\"台積電   \",\"4.50002 元／股\",\"\",\"0 股\"]]}";

		assertThat(respond2884(body).status()).isEqualTo(Status.FAILED);
	}

	@Test
	void 數字看不懂_FAILED() {
		String body = "{\"stat\":\"ok\",\"fields\":" + FIELDS
				+ ",\"data\":[[\"2884  \",\"玉山金   \",\"一點二 元／股\",\"\",\"10 股\"]]}";

		assertThat(respond2884(body).status()).isEqualTo(Status.FAILED);
	}

	@Test
	void 缺少現金股利欄位_FAILED() {
		String body = """
				{"stat":"ok","fields":["股票代號","股票名稱","A. 按普通股股東持股比例每千股無償配股"],
				 "data":[["2884  ","玉山金   ","10 股"]]}
				""";

		assertThat(respond2884(body).status()).isEqualTo(Status.FAILED);
	}

	@Test
	void 不認得的stat_FAILED() {
		assertThat(respond2884("{\"stat\":\"查詢太頻繁\"}").status()).isEqualTo(Status.FAILED);
	}

	@Test
	void 回傳HTML_BLOCKED_也算失敗() {
		DividendDetailResult result = respond2884("<html>blocked</html>");

		assertThat(result.status()).isEqualTo(Status.BLOCKED);
		assertThat(result.isFailure()).isTrue();
	}

	@Test
	void 測試伺服器沒有這支API_404_HTTP500_JSON壞掉_FAILED() {
		TwseDividendDetailClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_2884)).andRespond(withStatus(HttpStatus.NOT_FOUND));
		server.expect(requestTo(URL_2884)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
		server.expect(requestTo(URL_2884)).andRespond(withSuccess("{\"stat\":\"ok\",", MediaType.APPLICATION_JSON));

		assertThat(client.fetchDetail("2884", JUL_22).status()).isEqualTo(Status.FAILED);
		assertThat(client.fetchDetail("2884", JUL_22).status()).isEqualTo(Status.FAILED);
		assertThat(client.fetchDetail("2884", JUL_22).status()).isEqualTo(Status.FAILED);
	}
}
