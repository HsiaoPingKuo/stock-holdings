package io.hsiaopingkuo.stockholdings.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.quote.StockLookup.Status;
import tools.jackson.databind.json.JsonMapper;

/** 櫃買逐檔 client：不連網，用實測存下來的 JSON 模擬官方回應。 */
class TpexTradingStockClientTest {

	private static final String BASE_URL = "https://www.tpex.org.tw";
	private static final LocalDate NOV_23 = LocalDate.of(2026, 11, 23);

	private MockRestServiceServer server;

	private TpexTradingStockClient clientFor(String baseUrl) {
		RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
		server = MockRestServiceServer.bindTo(builder).build();
		return new TpexTradingStockClient(builder.build(), JsonMapper.builder().build());
	}

	private static ClassPathResource json(String file) {
		return new ClassPathResource("quotes/" + file);
	}

	@Test
	void 查到6488_名稱與收盤價() {
		TpexTradingStockClient client = clientFor(BASE_URL);
		server.expect(requestTo(BASE_URL + "/www/zh-tw/afterTrading/tradingStock?code=6488&date=2026/11/23&response=json"))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(json("tpex-trading-stock-6488.json"), MediaType.APPLICATION_JSON));

		StockLookup result = client.lookupMonth("6488", NOV_23);

		assertThat(result.status()).isEqualTo(Status.FOUND);
		assertThat(result.market()).isEqualTo(Market.TPEX);
		assertThat(result.name()).isEqualTo("環球晶");
		assertThat(result.closePrice()).isEqualByComparingTo("460.50");
		// 最外層 date=20261101 是查詢月份，資料日期要看最後一列
		assertThat(result.tradeDate()).isEqualTo(NOV_23);
		server.verify();
	}

	@Test
	void 上市股票查櫃買_stat是ok但沒有資料列_NOT_FOUND() {
		TpexTradingStockClient client = clientFor(BASE_URL);
		server.expect(requestTo(BASE_URL + "/www/zh-tw/afterTrading/tradingStock?code=2330&date=2026/11/24&response=json"))
				.andRespond(withSuccess(json("tpex-trading-stock-2330-not-found.json"), MediaType.APPLICATION_JSON));

		assertThat(client.lookupMonth("2330", LocalDate.of(2026, 11, 24)).status()).isEqualTo(Status.NOT_FOUND);
	}

	@Test
	void 主機網址換成別的_路徑和參數不變_日期斜線不被編碼() {
		TpexTradingStockClient client = clientFor("http://localhost:9000");
		server.expect(requestTo("http://localhost:9000/www/zh-tw/afterTrading/tradingStock?code=6488&date=2026/11/23&response=json"))
				.andRespond(withSuccess(json("tpex-trading-stock-6488.json"), MediaType.APPLICATION_JSON));

		assertThat(client.lookupMonth("6488", NOV_23).status()).isEqualTo(Status.FOUND);
		server.verify();
	}

	@Test
	void 回傳HTML_UNAVAILABLE() {
		TpexTradingStockClient client = clientFor(BASE_URL);
		server.expect(requestTo(BASE_URL + "/www/zh-tw/afterTrading/tradingStock?code=6488&date=2026/11/23&response=json"))
				.andRespond(withSuccess("<html><body>維護中</body></html>", MediaType.TEXT_HTML));

		assertThat(client.lookupMonth("6488", NOV_23).status()).isEqualTo(Status.UNAVAILABLE);
	}

	@Test
	void 逾時_UNAVAILABLE() {
		TpexTradingStockClient client = clientFor(BASE_URL);
		server.expect(requestTo(BASE_URL + "/www/zh-tw/afterTrading/tradingStock?code=6488&date=2026/11/23&response=json"))
				.andRespond(withException(new SocketTimeoutException("Read timed out")));

		assertThat(client.lookupMonth("6488", NOV_23).status()).isEqualTo(Status.UNAVAILABLE);
	}

	@Test
	void stat不是ok_UNAVAILABLE() {
		TpexTradingStockClient client = clientFor(BASE_URL);
		server.expect(requestTo(BASE_URL + "/www/zh-tw/afterTrading/tradingStock?code=6488&date=2026/11/23&response=json"))
				.andRespond(withSuccess("{\"stat\":\"error\"}", MediaType.APPLICATION_JSON));

		assertThat(client.lookupMonth("6488", NOV_23).status()).isEqualTo(Status.UNAVAILABLE);
	}
}
