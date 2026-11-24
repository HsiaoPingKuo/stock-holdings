package io.hsiaopingkuo.stockholdings.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
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

/** 證交所批次 MI_INDEX：不連網，用實測存下來的 JSON（2026-11-23）模擬官方回應。 */
class TwseMiIndexClientTest {

	private static final String BASE_URL = "https://www.twse.com.tw";
	private static final LocalDate NOV_23 = LocalDate.of(2026, 11, 23);
	private static final String URL_1123 = BASE_URL
			+ "/rwd/zh/afterTrading/MI_INDEX?date=20261123&type=ALLBUT0999&response=json";

	private MockRestServiceServer server;

	private TwseMiIndexClient clientFor(String baseUrl) {
		RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
		server = MockRestServiceServer.bindTo(builder).build();
		return new TwseMiIndexClient(builder.build(), JsonMapper.builder().build());
	}

	private static DailyQuote quoteOf(BatchQuoteResult result, String code) {
		return result.quotes().stream().filter(q -> q.code().equals(code)).findFirst().orElseThrow();
	}

	@Test
	void 實測資料_用欄位名稱找到收盤行情表_2330收盤價2030元_日期11月23日() {
		TwseMiIndexClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_1123))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(new ClassPathResource("quotes/twse-mi-index-20261123.json"),
						MediaType.APPLICATION_JSON));

		BatchQuoteResult result = client.fetchDay(NOV_23);

		assertThat(result.status()).isEqualTo(Status.OK);
		assertThat(result.tradeDate()).isEqualTo(NOV_23);
		// 收盤行情表共 1394 列，全部都要讀進來（包含當天沒成交的）
		assertThat(result.quotes()).hasSize(1394);
		DailyQuote tsmc = quoteOf(result, "2330");
		assertThat(tsmc.name()).isEqualTo("台積電");
		assertThat(tsmc.market()).isEqualTo(Market.TWSE);
		assertThat(tsmc.tradeDate()).isEqualTo(NOV_23);
		assertThat(tsmc.closePrice()).isEqualByComparingTo("2030.00");
		assertThat(tsmc.closeDate()).isEqualTo(NOV_23);
		server.verify();
	}

	@Test
	void 實測資料_當天沒有成交的代號_收盤價和收盤日是null_交易日仍是當天() {
		TwseMiIndexClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_1123))
				.andRespond(withSuccess(new ClassPathResource("quotes/twse-mi-index-20261123.json"),
						MediaType.APPLICATION_JSON));

		// 00625K 在 11/23 的收盤價是 "--"
		DailyQuote noTrade = quoteOf(client.fetchDay(NOV_23), "00625K");

		assertThat(noTrade.closePrice()).isNull();
		assertThat(noTrade.closeDate()).isNull();
		assertThat(noTrade.tradeDate()).isEqualTo(NOV_23);
	}

	@Test
	void 收盤行情表換了位置_仍然找得到() {
		TwseMiIndexClient client = clientFor(BASE_URL);
		String body = """
				{"stat":"OK","date":"20261124","tables":[
				 {"title":"115年11月24日 每日收盤行情(全部)","fields":["證券代號","證券名稱","收盤價"],
				  "data":[["2330","台積電","2,050.00"]]},
				 {"title":"大盤統計資訊","fields":["成交統計","成交金額(元)"],"data":[["1.一般股票","100"]]}]}
				""";
		server.expect(requestTo(BASE_URL + "/rwd/zh/afterTrading/MI_INDEX?date=20261124&type=ALLBUT0999&response=json"))
				.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		BatchQuoteResult result = client.fetchDay(LocalDate.of(2026, 11, 24));

		assertThat(result.status()).isEqualTo(Status.OK);
		assertThat(result.tradeDate()).isEqualTo(LocalDate.of(2026, 11, 24));
		assertThat(quoteOf(result, "2330").closePrice()).isEqualByComparingTo("2050.00");
	}

	@Test
	void 標題沒有日期_改用最外層的西元日期() {
		TwseMiIndexClient client = clientFor(BASE_URL);
		String body = """
				{"stat":"OK","date":"20261124","tables":[
				 {"title":"每日收盤行情","fields":["證券代號","證券名稱","收盤價"],"data":[["2330","台積電","2,050.00"]]}]}
				""";
		server.expect(requestTo(BASE_URL + "/rwd/zh/afterTrading/MI_INDEX?date=20261124&type=ALLBUT0999&response=json"))
				.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		assertThat(client.fetchDay(LocalDate.of(2026, 11, 24)).tradeDate()).isEqualTo(LocalDate.of(2026, 11, 24));
	}

	@Test
	void stat不是OK_還沒公布或休市_NO_DATA() {
		TwseMiIndexClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_1123))
				.andRespond(withSuccess("{\"stat\":\"很抱歉，沒有符合條件的資料!\",\"total\":0}", MediaType.APPLICATION_JSON));

		assertThat(client.fetchDay(NOV_23).status()).isEqualTo(Status.NO_DATA);
	}

	@Test
	void 找不到收盤行情表_FAILED() {
		TwseMiIndexClient client = clientFor(BASE_URL);
		String body = """
				{"stat":"OK","tables":[{"title":"大盤統計資訊","fields":["成交統計","成交金額(元)"],"data":[]}]}
				""";
		server.expect(requestTo(URL_1123)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		assertThat(client.fetchDay(NOV_23).status()).isEqualTo(Status.FAILED);
	}

	@Test
	void 測試伺服器沒有這支API_404_FAILED() {
		TwseMiIndexClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_1123)).andRespond(withStatus(HttpStatus.NOT_FOUND));

		assertThat(client.fetchDay(NOV_23).status()).isEqualTo(Status.FAILED);
	}

	@Test
	void 逾時_FAILED() {
		TwseMiIndexClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_1123)).andRespond(withException(new SocketTimeoutException("Read timed out")));

		assertThat(client.fetchDay(NOV_23).status()).isEqualTo(Status.FAILED);
	}

	@Test
	void 被擋403或429或回傳HTML_BLOCKED() {
		TwseMiIndexClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_1123)).andRespond(withStatus(HttpStatus.FORBIDDEN));
		server.expect(requestTo(URL_1123)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
		server.expect(requestTo(URL_1123)).andRespond(withSuccess("<html>blocked</html>", MediaType.TEXT_HTML));

		assertThat(client.fetchDay(NOV_23).status()).isEqualTo(Status.BLOCKED);
		assertThat(client.fetchDay(NOV_23).status()).isEqualTo(Status.BLOCKED);
		assertThat(client.fetchDay(NOV_23).status()).isEqualTo(Status.BLOCKED);
	}

	@Test
	void 主機網址換成別的_路徑和參數不變() {
		TwseMiIndexClient client = clientFor("http://localhost:9000");
		server.expect(requestTo("http://localhost:9000/rwd/zh/afterTrading/MI_INDEX?date=20261123&type=ALLBUT0999&response=json"))
				.andRespond(withSuccess(new ClassPathResource("quotes/twse-mi-index-20261123.json"),
						MediaType.APPLICATION_JSON));

		assertThat(client.fetchDay(NOV_23).status()).isEqualTo(Status.OK);
		server.verify();
	}
}
