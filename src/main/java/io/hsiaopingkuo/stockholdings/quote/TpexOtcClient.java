package io.hsiaopingkuo.stockholdings.quote;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import io.hsiaopingkuo.stockholdings.entity.Market;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 櫃買中心「上櫃股票每日收盤行情」（上櫃批次）：
 * {TPEX_BASE_URL}/www/zh-tw/afterTrading/otc?date=yyyy/MM/dd&type=EW&response=json
 * 資料在 tables[0]；欄位名稱前後有空白（"收盤 "），比對前先去掉。
 */
public class TpexOtcClient implements MarketCloseBatchClient {

	private static final Logger log = LoggerFactory.getLogger(TpexOtcClient.class);

	static final String PATH = "/www/zh-tw/afterTrading/otc";
	// 日期用斜線，和官方範例一致
	private static final DateTimeFormatter DATE_PARAM = DateTimeFormatter.ofPattern("yyyy/MM/dd");

	private final RestClient restClient;
	private final JsonMapper jsonMapper;

	public TpexOtcClient(RestClient restClient, JsonMapper jsonMapper) {
		this.restClient = restClient;
		this.jsonMapper = jsonMapper;
	}

	@Override
	public Market market() {
		return Market.TPEX;
	}

	@Override
	public BatchQuoteResult fetchDay(LocalDate date) {
		String description = "櫃買 otc " + date;
		// 用 queryParam 組網址：範本變數裡的 / 會被編碼成 %2F，跟官方範例不同
		QuoteHttp.Response response = QuoteHttp.getJson(restClient, jsonMapper, uriBuilder -> uriBuilder
				.path(PATH)
				.queryParam("date", date.format(DATE_PARAM))
				.queryParam("type", "EW")
				.queryParam("response", "json")
				.build(), description);
		if (response.outcome() != QuoteHttp.Outcome.OK) {
			return BatchQuoteResult.failure(response.outcome());
		}
		try {
			return parse(response.json(), description);
		} catch (RuntimeException e) {
			log.warn("{}：格式和預期不同：{}", description, e.toString());
			return BatchQuoteResult.failed();
		}
	}

	private BatchQuoteResult parse(JsonNode root, String description) {
		String stat = QuoteParsing.text(root.path("stat"));
		if (!QuoteParsing.isOk(stat)) {
			// 和證交所一致：stat 不是 ok 當成「這一天沒有資料」，記下 stat 方便追查
			log.info("{}：這一天沒有資料（stat={}）", description, stat);
			return BatchQuoteResult.noData();
		}
		JsonNode tables = root.path("tables");
		if (!tables.isArray() || tables.isEmpty()) {
			throw new IllegalArgumentException("沒有 tables");
		}
		JsonNode table = tables.get(0);
		JsonNode data = table.path("data");
		// 還沒公布或休市時 stat 仍是 ok，只是資料列是空的
		if (!data.isArray() || data.isEmpty()) {
			log.info("{}：這一天沒有資料（資料列是空的）", description);
			return BatchQuoteResult.noData();
		}
		String rocDate = QuoteParsing.text(table.path("date"));
		if (rocDate == null) {
			throw new IllegalArgumentException("tables[0] 沒有 date");
		}
		// tables[0].date 是民國日期（115/11/23），是資料本身的日期
		LocalDate tradeDate = QuoteParsing.parseRocDate(rocDate);
		List<DailyQuote> quotes = QuoteParsing.parseDailyRows(table.path("fields"), data,
				"代號", "名稱", "收盤", Market.TPEX, tradeDate);
		return BatchQuoteResult.ok(tradeDate, quotes);
	}
}
