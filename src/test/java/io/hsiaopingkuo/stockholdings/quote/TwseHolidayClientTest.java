package io.hsiaopingkuo.stockholdings.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

/** 證交所休市日表：不連網，用實測存下來的 holidaySchedule.json（2027 年，民國 116 年）。 */
class TwseHolidayClientTest {

	private static final String URL = "https://openapi.twse.com.tw/v1/holidaySchedule/holidaySchedule";

	private MockRestServiceServer server;

	private TwseHolidayClient clientFor(String url) {
		RestClient.Builder builder = RestClient.builder().baseUrl(url);
		server = MockRestServiceServer.bindTo(builder).build();
		return new TwseHolidayClient(builder.build(), JsonMapper.builder().build());
	}

	private Set<LocalDate> fetchRealSample() {
		TwseHolidayClient client = clientFor(URL);
		server.expect(requestTo(URL))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(new ClassPathResource("quotes/holidaySchedule.json"), MediaType.APPLICATION_JSON));
		Set<LocalDate> closed = client.fetchClosedDates().orElseThrow();
		server.verify();
		return closed;
	}

	@Test
	void 實測資料_國定假日是休市日() {
		Set<LocalDate> closed = fetchRealSample();

		// 民國 1160101 = 2027-01-01 開國紀念日；1160204 = 農曆除夕
		assertThat(closed).contains(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 2, 4), LocalDate.of(2027, 10, 11));
	}

	@Test
	void 實測資料_市場無交易僅辦理結算交割_當成休市日() {
		assertThat(fetchRealSample()).contains(LocalDate.of(2027, 2, 2), LocalDate.of(2027, 2, 3));
	}

	@Test
	void 實測資料_開始交易日和最後交易日_是交易日不是休市日() {
		Set<LocalDate> closed = fetchRealSample();

		assertThat(closed).doesNotContain(
				LocalDate.of(2027, 1, 4),   // 國曆新年開始交易日
				LocalDate.of(2027, 2, 1),   // 農曆春節前最後交易日
				LocalDate.of(2027, 2, 11)); // 農曆春節後開始交易日
	}

	@Test
	void 民國日期沒有斜線_最後4碼是月日() {
		assertThat(TwseHolidayClient.parseCompactRocDate("1160101")).isEqualTo(LocalDate.of(2027, 1, 1));
		assertThat(TwseHolidayClient.parseCompactRocDate("1151225")).isEqualTo(LocalDate.of(2026, 12, 25));
	}

	@Test
	void 測試伺服器沒有這支API_404_查不到() {
		TwseHolidayClient client = clientFor(URL);
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

		assertThat(client.fetchClosedDates()).isEmpty();
	}

	@Test
	void 回傳HTML_查不到() {
		TwseHolidayClient client = clientFor(URL);
		server.expect(requestTo(URL)).andRespond(withSuccess("<html>維護中</html>", MediaType.TEXT_HTML));

		assertThat(client.fetchClosedDates()).isEmpty();
	}

	@Test
	void 格式不符_不是陣列或日期看不懂_查不到() {
		TwseHolidayClient client = clientFor(URL);
		server.expect(requestTo(URL)).andRespond(withSuccess("{\"stat\":\"ok\"}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(URL))
				.andRespond(withSuccess("[{\"Name\":\"中秋節\",\"Date\":\"2027/09/15\"}]", MediaType.APPLICATION_JSON));

		assertThat(client.fetchClosedDates()).isEmpty();
		assertThat(client.fetchClosedDates()).isEmpty();
	}

	@Test
	void 網址可以整個換掉() {
		TwseHolidayClient client = clientFor("http://localhost:9000/holiday");
		server.expect(requestTo("http://localhost:9000/holiday")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		assertThat(client.fetchClosedDates()).contains(Set.of());
		server.verify();
	}
}
