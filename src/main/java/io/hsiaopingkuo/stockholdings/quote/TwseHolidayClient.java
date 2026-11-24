package io.hsiaopingkuo.stockholdings.quote;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 證交所 OpenAPI「市場開休市日期」：{TWSE_HOLIDAY_URL}，預設
 * https://openapi.twse.com.tw/v1/holidaySchedule/holidaySchedule
 * 主機和行情 API（www.twse.com.tw）不同，所以網址整個獨立設定。
 *
 * 回應是陣列：[{"Name":"中華民國開國紀念日","Date":"1160101","Weekday":"五","Description":"…"}, …]
 * 注意表裡不只有休市日：
 * - 名稱含「開始交易」「最後交易」的是交易日（例如「國曆新年開始交易日」），不能當休市
 * - 「市場無交易，僅辦理結算交割作業。」不開市，要當休市
 * 颱風假等臨時休市不會在表裡，那種情況靠排程「沒有資料就重試到 15:40」處理。
 */
public class TwseHolidayClient implements HolidayScheduleClient {

	private static final Logger log = LoggerFactory.getLogger(TwseHolidayClient.class);

	private final RestClient restClient;
	private final JsonMapper jsonMapper;

	/** @param restClient baseUrl 已經是完整的休市日表網址 */
	public TwseHolidayClient(RestClient restClient, JsonMapper jsonMapper) {
		this.restClient = restClient;
		this.jsonMapper = jsonMapper;
	}

	@Override
	public Optional<Set<LocalDate>> fetchClosedDates() {
		String description = "證交所休市日表";
		// 網址已經完整寫在 baseUrl，這裡不再加路徑或參數
		QuoteHttp.Response response = QuoteHttp.getJson(restClient, jsonMapper, uriBuilder -> uriBuilder.build(),
				description);
		if (response.outcome() != QuoteHttp.Outcome.OK) {
			return Optional.empty();
		}
		try {
			return Optional.of(parse(response.json()));
		} catch (RuntimeException e) {
			log.warn("{}：格式和預期不同：{}", description, e.toString());
			return Optional.empty();
		}
	}

	static Set<LocalDate> parse(JsonNode root) {
		if (!root.isArray()) {
			throw new IllegalArgumentException("回應不是陣列");
		}
		Set<LocalDate> closed = new HashSet<>();
		for (JsonNode row : root) {
			String name = QuoteParsing.text(row.path("Name"));
			String date = QuoteParsing.text(row.path("Date"));
			if (date == null || isTradingDay(name)) {
				continue;
			}
			closed.add(parseCompactRocDate(date));
		}
		return closed;
	}

	/** 「開始交易日」「最後交易日」是要開市的日子，只是被列在表裡提醒。 */
	static boolean isTradingDay(String name) {
		return name != null && (name.contains("開始交易") || name.contains("最後交易"));
	}

	/** 沒有斜線的民國日期 "1160101" → 2027-01-01：最後 4 碼是月日，前面是民國年。 */
	static LocalDate parseCompactRocDate(String rocDate) {
		String digits = rocDate.strip();
		if (!digits.matches("\\d{6,7}")) {
			throw new IllegalArgumentException("不是民國日期格式：" + rocDate);
		}
		int split = digits.length() - 4;
		return QuoteParsing.parseRocDate(digits.substring(0, split) + "/" + digits.substring(split, split + 2) + "/"
				+ digits.substring(split + 2));
	}
}
