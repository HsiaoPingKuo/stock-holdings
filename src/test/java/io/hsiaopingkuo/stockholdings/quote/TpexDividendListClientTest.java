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

import io.hsiaopingkuo.stockholdings.calc.DividendKind;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.quote.DividendListResult.Status;
import tools.jackson.databind.json.JsonMapper;

/** 上櫃除權息列表 exDailyQ：不連網，用實測存下來的 JSON（2026-11-25）模擬官方回應。 */
class TpexDividendListClientTest {

	private static final String BASE_URL = "https://www.tpex.org.tw";
	private static final LocalDate JUN_1 = LocalDate.of(2025, 6, 1);
	private static final LocalDate JUL_31 = LocalDate.of(2025, 7, 31);
	// 日期的斜線不能被編碼成 %2F
	private static final String URL_JUN_JUL = BASE_URL
			+ "/www/zh-tw/bulletin/exDailyQ?startDate=2025/06/01&endDate=2025/07/31&response=json";

	private MockRestServiceServer server;

	private TpexDividendListClient clientFor(String baseUrl) {
		RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
		server = MockRestServiceServer.bindTo(builder).build();
		return new TpexDividendListClient(builder.build(), JsonMapper.builder().build());
	}

	private DividendListResult sample() {
		TpexDividendListClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_JUN_JUL))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(new ClassPathResource("dividends/tpex-ex-daily-q-20250601-20250731.json"),
						MediaType.APPLICATION_JSON));
		DividendListResult result = client.fetchRange(JUN_1, JUL_31);
		server.verify();
		return result;
	}

	private DividendListResult respond(String body) {
		TpexDividendListClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_JUN_JUL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		return client.fetchRange(JUN_1, JUL_31);
	}

	private static DividendListRow rowOf(DividendListResult result, String code) {
		return result.rows().stream().filter(r -> r.code().equals(code)).findFirst().orElseThrow();
	}

	@Test
	void 實測資料_2025年6到7月共481列_8069每個欄位都正確() {
		DividendListResult result = sample();

		assertThat(result.status()).isEqualTo(Status.OK);
		assertThat(result.rows()).hasSize(481);
		DividendListRow eink = rowOf(result, "8069");
		assertThat(eink.market()).isEqualTo(Market.TPEX);
		assertThat(eink.exDate()).isEqualTo(LocalDate.of(2025, 7, 24));
		// 名稱尾端的空白要去掉
		assertThat(eink.name()).isEqualTo("元太");
		assertThat(eink.kind()).isEqualTo(DividendKind.CASH);
		assertThat(eink.prevClose()).isEqualByComparingTo("216.00");
		assertThat(eink.exRightReference()).isEqualByComparingTo("211.01");
		assertThat(eink.rightsPlusDividend()).isEqualByComparingTo("4.990139");
		assertThat(eink.dividendReference()).isEqualByComparingTo("211.01");
		// 上櫃列表直接有官方的現金股利和配股，而且是 8 位小數（比權值+息值多兩位）
		assertThat(eink.listedCashPerShare()).isEqualTo(new BigDecimal("4.99013930"));
		assertThat(eink.listedStockPerThousand()).isEqualByComparingTo("0");
		// 上櫃不用查明細
		assertThat(eink.detailStockNo()).isNull();
		assertThat(eink.detailDate()).isNull();
	}

	@Test
	void 實測資料_配股率的8位小數完整保留_6712是99點99999623() {
		DividendListRow row = rowOf(sample(), "6712");

		assertThat(row.kind()).isEqualTo(DividendKind.STOCK);
		assertThat(row.listedStockPerThousand()).isEqualTo(new BigDecimal("99.99999623"));
		assertThat(row.listedCashPerShare()).isEqualByComparingTo("0");
	}

	@Test
	void 實測資料_除權和除權息的種類對應() {
		DividendListResult result = sample();

		assertThat(rowOf(result, "6762").kind()).isEqualTo(DividendKind.CASH_AND_STOCK);
		assertThat(rowOf(result, "6762").listedCashPerShare()).isEqualByComparingTo("0.30000000");
		assertThat(rowOf(result, "6762").listedStockPerThousand()).isEqualByComparingTo("150.00000327");
		// 只有現金增資的除權：配股是 0
		assertThat(rowOf(result, "3349").kind()).isEqualTo(DividendKind.STOCK);
		assertThat(rowOf(result, "3349").listedStockPerThousand()).isEqualByComparingTo("0");
		assertThat(result.rows()).filteredOn(r -> r.kind() == DividendKind.CASH).hasSize(447);
		assertThat(result.rows()).filteredOn(r -> r.kind() == DividendKind.STOCK).hasSize(15);
		assertThat(result.rows()).filteredOn(r -> r.kind() == DividendKind.CASH_AND_STOCK).hasSize(19);
	}

	@Test
	void 實測資料_沒有資料時stat是ok但data是0列_NO_DATA不是失敗() {
		TpexDividendListClient client = clientFor(BASE_URL);
		server.expect(requestTo(BASE_URL + "/www/zh-tw/bulletin/exDailyQ?startDate=2025/01/27&endDate=2025/01/31&response=json"))
				.andRespond(withSuccess(new ClassPathResource("dividends/tpex-ex-daily-q-20250127-20250131-no-data.json"),
						MediaType.APPLICATION_JSON));

		DividendListResult result = client.fetchRange(LocalDate.of(2025, 1, 27), LocalDate.of(2025, 1, 31));

		assertThat(result.status()).isEqualTo(Status.NO_DATA);
		assertThat(result.isFailure()).isFalse();
	}

	@Test
	void stat不是ok_FAILED() {
		assertThat(respond("{\"stat\":\"error\"}").status()).isEqualTo(Status.FAILED);
	}

	@Test
	void 沒有tables_FAILED() {
		assertThat(respond("{\"stat\":\"ok\"}").status()).isEqualTo(Status.FAILED);
		assertThat(respond("{\"stat\":\"ok\",\"tables\":[]}").status()).isEqualTo(Status.FAILED);
	}

	@Test
	void 缺少現金股利欄位_FAILED() {
		String body = """
				{"stat":"ok","tables":[{"fields":["除權息日期","代號","名稱","除權息前收盤價","除權息參考價","權值+息值","權/息","減除股利參考價","每仟股無償配股"],
				 "data":[["114/07/24","8069","元太   ","216.00","211.01","4.990139","除息","211.01","0.00000000"]]}]}
				""";

		assertThat(respond(body).status()).isEqualTo(Status.FAILED);
	}

	@Test
	void 某一列日期看不懂_整份FAILED() {
		String body = """
				{"stat":"ok","tables":[{"fields":["除權息日期","代號","名稱","除權息前收盤價","除權息參考價","權值+息值","權/息","減除股利參考價","現金股利","每仟股無償配股"],
				 "data":[["114年07月24日","8069","元太   ","216.00","211.01","4.990139","除息","211.01","4.99013930","0.00000000"]]}]}
				""";

		assertThat(respond(body).status()).isEqualTo(Status.FAILED);
	}

	@Test
	void 回傳HTML_BLOCKED_也算失敗() {
		TpexDividendListClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_JUN_JUL)).andRespond(withSuccess("<!DOCTYPE html><html></html>", MediaType.TEXT_HTML));

		DividendListResult result = client.fetchRange(JUN_1, JUL_31);

		assertThat(result.status()).isEqualTo(Status.BLOCKED);
		assertThat(result.isFailure()).isTrue();
	}

	@Test
	void HTTP500_JSON壞掉_FAILED() {
		TpexDividendListClient client = clientFor(BASE_URL);
		server.expect(requestTo(URL_JUN_JUL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
		server.expect(requestTo(URL_JUN_JUL)).andRespond(withSuccess("{\"stat\":", MediaType.APPLICATION_JSON));

		assertThat(client.fetchRange(JUN_1, JUL_31).status()).isEqualTo(Status.FAILED);
		assertThat(client.fetchRange(JUN_1, JUL_31).status()).isEqualTo(Status.FAILED);
	}

	@Test
	void 換成主考官的主機_路徑和參數不變() {
		TpexDividendListClient client = clientFor("http://test-server:8082");
		server.expect(requestTo("http://test-server:8082/www/zh-tw/bulletin/exDailyQ?startDate=2025/06/01&endDate=2025/07/31&response=json"))
				.andRespond(withSuccess("{\"stat\":\"ok\",\"tables\":[{\"fields\":[],\"data\":[]}]}", MediaType.APPLICATION_JSON));

		assertThat(client.fetchRange(JUN_1, JUL_31).status()).isEqualTo(Status.NO_DATA);
		server.verify();
	}
}
