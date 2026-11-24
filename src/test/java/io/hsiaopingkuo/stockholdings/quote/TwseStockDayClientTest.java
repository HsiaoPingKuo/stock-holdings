package io.hsiaopingkuo.stockholdings.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
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
import io.hsiaopingkuo.stockholdings.quote.StockLookup.Status;
import tools.jackson.databind.json.JsonMapper;

/**
 * 證交所逐檔 client：不連網，用 MockRestServiceServer 假裝官方，回應內容是實測存下來的 JSON。
 */
class TwseStockDayClientTest {

	private static final String BASE_URL = "https://www.twse.com.tw";
	private static final LocalDate NOV_23 = LocalDate.of(2026, 11, 23);

	private MockRestServiceServer server;

	private TwseStockDayClient clientFor(String baseUrl) {
		RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
		// bindTo 要在 build() 之前：把真正送出請求的元件換成假的
		server = MockRestServiceServer.bindTo(builder).build();
		return new TwseStockDayClient(builder.build(), JsonMapper.builder().build());
	}

	private static String url(String baseUrl, String date, String code) {
		return baseUrl + "/rwd/zh/afterTrading/STOCK_DAY?date=" + date + "&stockNo=" + code + "&response=json";
	}

	private static ClassPathResource json(String file) {
		return new ClassPathResource("quotes/" + file);
	}

	@Test
	void 查到2330_名稱去掉尾端空白_收盤價與日期取最後一列() {
		TwseStockDayClient client = clientFor(BASE_URL);
		server.expect(requestTo(url(BASE_URL, "20261123", "2330")))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(json("twse-stock-day-2330.json"), MediaType.APPLICATION_JSON));

		StockLookup result = client.lookupMonth("2330", NOV_23);

		assertThat(result.status()).isEqualTo(Status.FOUND);
		assertThat(result.market()).isEqualTo(Market.TWSE);
		assertThat(result.name()).isEqualTo("台積電");
		assertThat(result.closePrice()).isEqualByComparingTo("2030.00");
		assertThat(result.tradeDate()).isEqualTo(NOV_23);
		server.verify();
	}

	@Test
	void 查不到9999_NOT_FOUND() {
		TwseStockDayClient client = clientFor(BASE_URL);
		server.expect(requestTo(url(BASE_URL, "20261124", "9999")))
				.andRespond(withSuccess(json("twse-stock-day-9999-not-found.json"), MediaType.APPLICATION_JSON));

		StockLookup result = client.lookupMonth("9999", LocalDate.of(2026, 11, 24));

		assertThat(result.status()).isEqualTo(Status.NOT_FOUND);
	}

	@Test
	void 主機網址換成別的_路徑和參數不變() {
		String testServer = "http://localhost:9000";
		TwseStockDayClient client = clientFor(testServer);
		server.expect(requestTo("http://localhost:9000/rwd/zh/afterTrading/STOCK_DAY?date=20261123&stockNo=2330&response=json"))
				.andRespond(withSuccess(json("twse-stock-day-2330.json"), MediaType.APPLICATION_JSON));

		assertThat(client.lookupMonth("2330", NOV_23).status()).isEqualTo(Status.FOUND);
		server.verify();
	}

	@Test
	void 回傳HTML_UNAVAILABLE() {
		TwseStockDayClient client = clientFor(BASE_URL);
		server.expect(requestTo(url(BASE_URL, "20261123", "2330")))
				.andRespond(withSuccess("<!DOCTYPE html><html><body>請稍後再試</body></html>", MediaType.TEXT_HTML));

		assertThat(client.lookupMonth("2330", NOV_23).status()).isEqualTo(Status.UNAVAILABLE);
	}

	@Test
	void ContentType寫JSON但內容是HTML_仍然UNAVAILABLE() {
		TwseStockDayClient client = clientFor(BASE_URL);
		server.expect(requestTo(url(BASE_URL, "20261123", "2330")))
				.andRespond(withSuccess("  <html><body>blocked</body></html>", MediaType.APPLICATION_JSON));

		assertThat(client.lookupMonth("2330", NOV_23).status()).isEqualTo(Status.UNAVAILABLE);
	}

	@Test
	void HTTP500_UNAVAILABLE() {
		TwseStockDayClient client = clientFor(BASE_URL);
		server.expect(requestTo(url(BASE_URL, "20261123", "2330"))).andRespond(withServerError());

		assertThat(client.lookupMonth("2330", NOV_23).status()).isEqualTo(Status.UNAVAILABLE);
	}

	@Test
	void 被擋403_UNAVAILABLE() {
		TwseStockDayClient client = clientFor(BASE_URL);
		server.expect(requestTo(url(BASE_URL, "20261123", "2330"))).andRespond(withStatus(HttpStatus.FORBIDDEN));

		assertThat(client.lookupMonth("2330", NOV_23).status()).isEqualTo(Status.UNAVAILABLE);
	}

	@Test
	void 逾時_UNAVAILABLE() {
		TwseStockDayClient client = clientFor(BASE_URL);
		server.expect(requestTo(url(BASE_URL, "20261123", "2330")))
				.andRespond(withException(new SocketTimeoutException("Read timed out")));

		assertThat(client.lookupMonth("2330", NOV_23).status()).isEqualTo(Status.UNAVAILABLE);
	}

	@Test
	void JSON格式壞掉_UNAVAILABLE() {
		TwseStockDayClient client = clientFor(BASE_URL);
		server.expect(requestTo(url(BASE_URL, "20261123", "2330")))
				.andRespond(withSuccess("{\"stat\":\"OK\",", MediaType.APPLICATION_JSON));

		assertThat(client.lookupMonth("2330", NOV_23).status()).isEqualTo(Status.UNAVAILABLE);
	}

	@Test
	void 看不懂的stat_不當成查不到而是UNAVAILABLE() {
		TwseStockDayClient client = clientFor(BASE_URL);
		server.expect(requestTo(url(BASE_URL, "20261123", "2330")))
				.andRespond(withSuccess("{\"stat\":\"查詢過於頻繁，請稍後再試\"}", MediaType.APPLICATION_JSON));

		assertThat(client.lookupMonth("2330", NOV_23).status()).isEqualTo(Status.UNAVAILABLE);
	}

	@Test
	void 最後一天沒有成交_收盤價取前一個有成交的列_最近交易日仍是最後一天() {
		// 第 3 題第二階段改規則：原本收盤價是 null，改成沿用前一個有成交日的收盤價
		TwseStockDayClient client = clientFor(BASE_URL);
		String body = """
				{"stat":"OK","title":"115年11月 1234 測試股   各日成交資訊",
				 "fields":["日期","成交股數","收盤價"],
				 "data":[["115/11/20","1,000","10.00"],["115/11/23","0","--"]]}
				""";
		server.expect(requestTo(url(BASE_URL, "20261123", "1234")))
				.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		StockLookup result = client.lookupMonth("1234", NOV_23);

		assertThat(result.status()).isEqualTo(Status.FOUND);
		assertThat(result.name()).isEqualTo("測試股");
		assertThat(result.tradeDate()).isEqualTo(NOV_23);
		assertThat(result.closePrice()).isEqualByComparingTo("10.00");
		assertThat(result.closeDate()).isEqualTo(LocalDate.of(2026, 11, 20));
	}

	@Test
	void 查到2330_最後一天有成交_收盤日等於最近交易日() {
		TwseStockDayClient client = clientFor(BASE_URL);
		server.expect(requestTo(url(BASE_URL, "20261123", "2330")))
				.andRespond(withSuccess(json("twse-stock-day-2330.json"), MediaType.APPLICATION_JSON));

		StockLookup result = client.lookupMonth("2330", NOV_23);

		assertThat(result.closeDate()).isEqualTo(result.tradeDate());
	}

	@Test
	void 從title切出名稱() {
		assertThat(TwseStockDayClient.parseName("2330", "115年11月 2330 台積電           各日成交資訊")).isEqualTo("台積電");
		assertThat(TwseStockDayClient.parseName("0050", "115年11月 0050 元大台灣50 各日成交資訊")).isEqualTo("元大台灣50");
		assertThat(TwseStockDayClient.parseName("2330", "格式改了")).isNull();
		assertThat(TwseStockDayClient.parseName("2330", null)).isNull();
	}
}
