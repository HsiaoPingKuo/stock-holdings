package io.hsiaopingkuo.stockholdings.quote;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.quote.QuoteParsing.DailyClose;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 櫃買中心「個股日成交資訊」（上櫃）：
 * {TPEX_BASE_URL}/www/zh-tw/afterTrading/tradingStock?code={代號}&date=yyyy/MM/dd&response=json
 * 一次回傳整個月每個交易日的資料。
 */
public class TpexTradingStockClient implements StockLookupClient {

	private static final Logger log = LoggerFactory.getLogger(TpexTradingStockClient.class);

	static final String PATH = "/www/zh-tw/afterTrading/tradingStock";
	// 日期用斜線，和官方範例一致
	private static final DateTimeFormatter DATE_PARAM = DateTimeFormatter.ofPattern("yyyy/MM/dd");

	private final RestClient restClient;
	private final JsonMapper jsonMapper;

	public TpexTradingStockClient(RestClient restClient, JsonMapper jsonMapper) {
		this.restClient = restClient;
		this.jsonMapper = jsonMapper;
	}

	@Override
	public Market market() {
		return Market.TPEX;
	}

	@Override
	public StockLookup lookupMonth(String code, LocalDate dayInMonth) {
		String description = "櫃買 tradingStock " + code + " " + dayInMonth;
		// 用 queryParam 組網址（而不是 "?date={date}" 範本變數）：範本變數裡的 / 會被編碼成 %2F，跟官方範例不同
		Optional<JsonNode> json = QuoteHttp.getJson(restClient, jsonMapper, uriBuilder -> uriBuilder
				.path(PATH)
				.queryParam("code", code)
				.queryParam("date", dayInMonth.format(DATE_PARAM))
				.queryParam("response", "json")
				.build(), description);
		if (json.isEmpty()) {
			return StockLookup.unavailable(Market.TPEX);
		}
		try {
			return parse(code, json.get());
		} catch (RuntimeException e) {
			log.warn("{}：格式和預期不同：{}", description, e.toString());
			return StockLookup.unavailable(Market.TPEX);
		}
	}

	private StockLookup parse(String code, JsonNode root) {
		String stat = QuoteParsing.text(root.path("stat"));
		if (!QuoteParsing.isOk(stat)) {
			log.warn("櫃買 tradingStock {}：無法辨識的 stat：{}", code, stat);
			return StockLookup.unavailable(Market.TPEX);
		}
		JsonNode tables = root.path("tables");
		if (!tables.isArray() || tables.isEmpty()) {
			throw new IllegalArgumentException("沒有 tables");
		}
		JsonNode table = tables.get(0);
		JsonNode data = table.path("data");
		// 櫃買查不到時 stat 仍然是 ok，只是 data 是空陣列，所以要看資料列，不能只看 stat
		if (!data.isArray() || data.isEmpty()) {
			return StockLookup.notFound(Market.TPEX);
		}
		DailyClose last = QuoteParsing.lastDailyClose(table.path("fields"), data, "收盤");
		String name = QuoteParsing.text(root.path("name"));
		name = (name == null || name.isBlank()) ? null : name.strip();
		return StockLookup.found(Market.TPEX, name, last.date(), last.closePrice());
	}
}
