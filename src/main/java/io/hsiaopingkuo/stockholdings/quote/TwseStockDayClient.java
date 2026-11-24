package io.hsiaopingkuo.stockholdings.quote;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.quote.QuoteParsing.MonthSummary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 證交所「個股日成交資訊」（上市）：
 * {TWSE_BASE_URL}/rwd/zh/afterTrading/STOCK_DAY?date=yyyyMMdd&stockNo={代號}&response=json
 * 一次回傳整個月每個交易日的資料。
 */
public class TwseStockDayClient implements StockLookupClient {

	private static final Logger log = LoggerFactory.getLogger(TwseStockDayClient.class);

	static final String PATH = "/rwd/zh/afterTrading/STOCK_DAY";
	private static final DateTimeFormatter DATE_PARAM = DateTimeFormatter.ofPattern("yyyyMMdd");
	/** 證交所查不到時 stat 會是「很抱歉，沒有符合條件的資料!」；代號不存在、這個月還沒交易日都是這句。 */
	private static final String NO_DATA_MESSAGE = "沒有符合條件的資料";

	private final RestClient restClient;
	private final JsonMapper jsonMapper;

	public TwseStockDayClient(RestClient restClient, JsonMapper jsonMapper) {
		this.restClient = restClient;
		this.jsonMapper = jsonMapper;
	}

	@Override
	public Market market() {
		return Market.TWSE;
	}

	@Override
	public StockLookup lookupMonth(String code, LocalDate dayInMonth) {
		String description = "證交所 STOCK_DAY " + code + " " + dayInMonth;
		Optional<JsonNode> json = QuoteHttp.getJson(restClient, jsonMapper, uriBuilder -> uriBuilder
				.path(PATH)
				.queryParam("date", dayInMonth.format(DATE_PARAM))
				.queryParam("stockNo", code)
				.queryParam("response", "json")
				.build(), description);
		if (json.isEmpty()) {
			return StockLookup.unavailable(Market.TWSE);
		}
		try {
			return parse(code, json.get());
		} catch (RuntimeException e) {
			// 欄位缺漏、日期或價格格式不對：官方格式可能改了，這份資料不可信
			log.warn("{}：格式和預期不同：{}", description, e.toString());
			return StockLookup.unavailable(Market.TWSE);
		}
	}

	private StockLookup parse(String code, JsonNode root) {
		String stat = QuoteParsing.text(root.path("stat"));
		if (QuoteParsing.isOk(stat)) {
			JsonNode data = root.path("data");
			if (!data.isArray() || data.isEmpty()) {
				return StockLookup.notFound(Market.TWSE);
			}
			MonthSummary month = QuoteParsing.summarizeMonth(root.path("fields"), data, "收盤價");
			return StockLookup.found(Market.TWSE, parseName(code, QuoteParsing.text(root.path("title"))),
					month.lastTradeDate(), month.closePrice(), month.closeDate());
		}
		if (stat != null && stat.contains(NO_DATA_MESSAGE)) {
			return StockLookup.notFound(Market.TWSE);
		}
		// 其他 stat（例如查詢太頻繁的提示）不能當成「代號不存在」，否則會誤判使用者打錯代號
		log.warn("證交所 STOCK_DAY {}：無法辨識的 stat：{}", code, stat);
		return StockLookup.unavailable(Market.TWSE);
	}

	/**
	 * 名稱藏在 title 裡："115年11月 2330 台積電           各日成交資訊"，名稱後面有一串空白。
	 * 取「代號」和「各日成交資訊」中間那段並去掉空白；切不出來時回 null（不影響判斷市場）。
	 */
	static String parseName(String code, String title) {
		if (title == null) {
			return null;
		}
		Pattern pattern = Pattern.compile("(?:^|\\s)" + Pattern.quote(code) + "\\s+(.+?)\\s*各日成交資訊");
		Matcher matcher = pattern.matcher(title);
		if (!matcher.find()) {
			return null;
		}
		String name = matcher.group(1).strip();
		return name.isEmpty() ? null : name;
	}
}
