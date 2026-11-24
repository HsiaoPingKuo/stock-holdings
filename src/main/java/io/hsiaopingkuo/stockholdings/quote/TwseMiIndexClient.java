package io.hsiaopingkuo.stockholdings.quote;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import io.hsiaopingkuo.stockholdings.entity.Market;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 證交所「每日收盤行情（全部，不含權證、牛熊證）」（上市批次）：
 * {TWSE_BASE_URL}/rwd/zh/afterTrading/MI_INDEX?date=yyyyMMdd&type=ALLBUT0999&response=json
 * 一次回傳約 9～10 張表（指數、大盤統計、個股收盤…），要用欄位名稱找出個股收盤那一張。
 */
public class TwseMiIndexClient implements MarketCloseBatchClient {

	private static final Logger log = LoggerFactory.getLogger(TwseMiIndexClient.class);

	static final String PATH = "/rwd/zh/afterTrading/MI_INDEX";
	private static final DateTimeFormatter DATE_PARAM = DateTimeFormatter.ofPattern("yyyyMMdd");
	private static final String CODE_FIELD = "證券代號";
	private static final String NAME_FIELD = "證券名稱";
	private static final String CLOSE_FIELD = "收盤價";
	/** 表的標題開頭是民國日期，例如「115年11月23日 每日收盤行情(…)」。 */
	private static final Pattern TITLE_DATE = Pattern.compile("(\\d{2,3})年(\\d{1,2})月(\\d{1,2})日");

	private final RestClient restClient;
	private final JsonMapper jsonMapper;

	public TwseMiIndexClient(RestClient restClient, JsonMapper jsonMapper) {
		this.restClient = restClient;
		this.jsonMapper = jsonMapper;
	}

	@Override
	public Market market() {
		return Market.TWSE;
	}

	@Override
	public BatchQuoteResult fetchDay(LocalDate date) {
		String description = "證交所 MI_INDEX " + date;
		QuoteHttp.Response response = QuoteHttp.getJson(restClient, jsonMapper, uriBuilder -> uriBuilder
				.path(PATH)
				.queryParam("date", date.format(DATE_PARAM))
				.queryParam("type", "ALLBUT0999")
				.queryParam("response", "json")
				.build(), description);
		if (response.outcome() != QuoteHttp.Outcome.OK) {
			return BatchQuoteResult.failure(response.outcome());
		}
		try {
			return parse(response.json(), description);
		} catch (RuntimeException e) {
			// 找不到收盤行情表、日期或價格格式不對：官方格式可能改了，改走逐檔
			log.warn("{}：格式和預期不同：{}", description, e.toString());
			return BatchQuoteResult.failed();
		}
	}

	private BatchQuoteResult parse(JsonNode root, String description) {
		String stat = QuoteParsing.text(root.path("stat"));
		if (!QuoteParsing.isOk(stat)) {
			// 還沒公布、休市都會回「很抱歉，沒有符合條件的資料!」：不是錯誤，稍後再查就好
			log.info("{}：這一天沒有資料（stat={}）", description, stat);
			return BatchQuoteResult.noData();
		}
		JsonNode table = findCloseTable(root.path("tables"));
		JsonNode data = table.path("data");
		if (!data.isArray() || data.isEmpty()) {
			return BatchQuoteResult.noData();
		}
		LocalDate tradeDate = tradeDate(table, root);
		List<DailyQuote> quotes = QuoteParsing.parseDailyRows(table.path("fields"), data,
				CODE_FIELD, NAME_FIELD, CLOSE_FIELD, Market.TWSE, tradeDate);
		return BatchQuoteResult.ok(tradeDate, quotes);
	}

	/** 用欄位名稱找表，不寫死 tables[8]：官方增減前面的指數表時才不會讀錯。 */
	static JsonNode findCloseTable(JsonNode tables) {
		for (JsonNode table : tables) {
			JsonNode fields = table.path("fields");
			if (QuoteParsing.indexOfField(fields, CODE_FIELD) >= 0
					&& QuoteParsing.indexOfField(fields, NAME_FIELD) >= 0
					&& QuoteParsing.indexOfField(fields, CLOSE_FIELD) >= 0) {
				return table;
			}
		}
		throw new IllegalArgumentException("找不到每日收盤行情表");
	}

	/**
	 * 資料日期：MI_INDEX 的資料列沒有日期欄，改用收盤行情表標題裡的民國日期（資料本身標示的日期）；
	 * 標題裡找不到時，才用最外層的 date（西元 yyyyMMdd）。
	 */
	private static LocalDate tradeDate(JsonNode table, JsonNode root) {
		String title = QuoteParsing.text(table.path("title"));
		if (title != null) {
			Matcher matcher = TITLE_DATE.matcher(title);
			if (matcher.find()) {
				return QuoteParsing.parseRocDate(matcher.group(1) + "/" + matcher.group(2) + "/" + matcher.group(3));
			}
		}
		String rootDate = QuoteParsing.text(root.path("date"));
		if (rootDate == null) {
			throw new IllegalArgumentException("找不到資料日期");
		}
		return LocalDate.parse(rootDate.strip(), DATE_PARAM);
	}
}
