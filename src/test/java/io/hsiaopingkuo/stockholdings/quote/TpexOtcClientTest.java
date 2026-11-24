package io.hsiaopingkuo.stockholdings.quote;

import static org.assertj.core.api.Assertions.assertThat;
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

import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.quote.BatchQuoteResult.Status;
import tools.jackson.databind.json.JsonMapper;

/** 櫃買批次 otc：不連網，用實測存下來的 JSON（2026-11-23）模擬官方回應。 */
class TpexOtcClientTest {

	private static final String BASE_URL = "https://www.tpex.org.tw";
	private static final LocalDate NOV_23 = LocalDate.of(2026, 11, 23);
	// 日期的斜線不能被編碼成 %2F
	private static final String URL_1123 = BASE_URL + "/www/zh-tw/afterTrading/otc?date=2026/11/23&type=EW&response=json";

	private MockRestServiceServer server;

	private TpexOtcClient clientFor(String baseUrl) {
		RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
		server = MockRestServiceServer.bindTo(builder).build();
		return new TpexOtcClient(builder.build(), JsonMapper.builder().build());
	}

	private static DailyQuote quoteOf(BatchQuoteResult result, String code) {
		return result.quotes().stream().filter(q -> q.code().equals(code)).findFirst().orElseThrow();
	}

	@Test
	void 實測資料_6488收盤價460點50_日期11月23日() {
		TpexOtcClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_1123))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(new ClassPathResource("quotes/tpex-otc-20261123.json"), MediaType.APPLICATION_JSON));

		BatchQuoteResult result = client.fetchDay(NOV_23);

		assertThat(result.status()).isEqualTo(Status.OK);
		// tables[0].date 是民國 115/11/23
		assertThat(result.tradeDate()).isEqualTo(NOV_23);
		assertThat(result.quotes()).hasSize(1020);
		DailyQuote gw = quoteOf(result, "6488");
		assertThat(gw.name()).isEqualTo("環球晶");
		assertThat(gw.market()).isEqualTo(Market.TPEX);
		assertThat(gw.closePrice()).isEqualByComparingTo("460.50");
		assertThat(gw.closeDate()).isEqualTo(NOV_23);
		server.verify();
	}

	@Test
	void 實測資料_沒有成交的收盤價是四個減號_當成沒有收盤價而不是略過() {
		TpexOtcClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_1123))
				.andRespond(withSuccess(new ClassPathResource("quotes/tpex-otc-20261123.json"), MediaType.APPLICATION_JSON));

		// 00986B 在 11/23 的收盤是 "----"
		DailyQuote noTrade = quoteOf(client.fetchDay(NOV_23), "00986B");

		assertThat(noTrade.closePrice()).isNull();
		assertThat(noTrade.tradeDate()).isEqualTo(NOV_23);
	}

	@Test
	void 資料列是空的_還沒公布或休市_NO_DATA() {
		TpexOtcClient client = clientFor(BASE_URL);
		String body = """
				{"stat":"ok","tables":[{"date":"115/11/24","fields":["代號","名稱","收盤 "],"data":[]}]}
				""";
		server.expect(requestTo(BASE_URL + "/www/zh-tw/afterTrading/otc?date=2026/11/24&type=EW&response=json"))
				.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		assertThat(client.fetchDay(LocalDate.of(2026, 11, 24)).status()).isEqualTo(Status.NO_DATA);
	}

	@Test
	void stat不是ok_NO_DATA() {
		TpexOtcClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_1123)).andRespond(withSuccess("{\"stat\":\"error\"}", MediaType.APPLICATION_JSON));

		assertThat(client.fetchDay(NOV_23).status()).isEqualTo(Status.NO_DATA);
	}

	@Test
	void 沒有tables或找不到收盤欄位_FAILED() {
		TpexOtcClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_1123)).andRespond(withSuccess("{\"stat\":\"ok\"}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(URL_1123)).andRespond(withSuccess("""
				{"stat":"ok","tables":[{"date":"115/11/23","fields":["代號","名稱"],"data":[["6488","環球晶"]]}]}
				""", MediaType.APPLICATION_JSON));

		assertThat(client.fetchDay(NOV_23).status()).isEqualTo(Status.FAILED);
		assertThat(client.fetchDay(NOV_23).status()).isEqualTo(Status.FAILED);
	}

	@Test
	void 測試伺服器沒有這支API_404_FAILED() {
		TpexOtcClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_1123)).andRespond(withStatus(HttpStatus.NOT_FOUND));

		assertThat(client.fetchDay(NOV_23).status()).isEqualTo(Status.FAILED);
	}

	@Test
	void 被擋403或回傳HTML_BLOCKED() {
		TpexOtcClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_1123)).andRespond(withStatus(HttpStatus.FORBIDDEN));
		server.expect(requestTo(URL_1123)).andRespond(withSuccess("<html>維護中</html>", MediaType.TEXT_HTML));

		assertThat(client.fetchDay(NOV_23).status()).isEqualTo(Status.BLOCKED);
		assertThat(client.fetchDay(NOV_23).status()).isEqualTo(Status.BLOCKED);
	}
}
