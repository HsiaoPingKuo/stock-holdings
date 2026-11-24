package io.hsiaopingkuo.stockholdings.quote;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 證交所「除權除息計算結果表」的明細（上市）：
 * {TWSE_BASE_URL}/rwd/zh/exRight/TWT49UDetail?STK_NO={代號}&T1=yyyyMMdd&response=json
 * 參數來自列表的「詳細資料」欄位。題目只提供列表，主考官的測試伺服器可能沒有這支 API，
 * 所以失敗時呼叫端要改用列表的值判斷（DividendSourceRule）。
 */
public class TwseDividendDetailClient implements DividendDetailClient {

	private static final Logger log = LoggerFactory.getLogger(TwseDividendDetailClient.class);

	static final String PATH = "/rwd/zh/exRight/TWT49UDetail";
	private static final DateTimeFormatter DATE_PARAM = DateTimeFormatter.ofPattern("yyyyMMdd");
	/** 查不到時是 HTTP 200、stat「無相關資料」。 */
	private static final String NOT_FOUND_MESSAGE = "無相關資料";
	private static final String CODE_FIELD = "股票代號";
	private static final String CASH_FIELD = "(每股配發現金股利)除息";
	private static final String STOCK_FIELD = "A. 按普通股股東持股比例每千股無償配股";

	private final RestClient restClient;
	private final JsonMapper jsonMapper;

	public TwseDividendDetailClient(RestClient restClient, JsonMapper jsonMapper) {
		this.restClient = restClient;
		this.jsonMapper = jsonMapper;
	}

	@Override
	public DividendDetailResult fetchDetail(String stockNo, LocalDate exDate) {
		String description = "證交所 TWT49UDetail " + stockNo + " " + exDate;
		QuoteHttp.Response response = QuoteHttp.getJson(restClient, jsonMapper, uriBuilder -> uriBuilder
				.path(PATH)
				.queryParam("STK_NO", stockNo)
				.queryParam("T1", exDate.format(DATE_PARAM))
				.queryParam("response", "json")
				.build(), description);
		if (response.outcome() != QuoteHttp.Outcome.OK) {
			return DividendDetailResult.failure(response.outcome());
		}
		try {
			return parse(stockNo, response.json(), description);
		} catch (RuntimeException e) {
			// 缺欄位、代號對不上、數字看不懂：這份明細不可信，之後重試
			log.warn("{}：格式和預期不同：{}", description, e.getMessage());
			return DividendDetailResult.failed();
		}
	}

	private DividendDetailResult parse(String stockNo, JsonNode root, String description) {
		String stat = QuoteParsing.text(root.path("stat"));
		if (!QuoteParsing.isOk(stat)) {
			if (stat != null && stat.contains(NOT_FOUND_MESSAGE)) {
				log.info("{}：沒有明細", description);
				return DividendDetailResult.notFound();
			}
			log.warn("{}：無法辨識的 stat：{}", description, stat);
			return DividendDetailResult.failed();
		}
		JsonNode data = root.path("data");
		if (!data.isArray()) {
			throw new IllegalArgumentException("stat 是 ok 但沒有 data");
		}
		if (data.isEmpty()) {
			log.info("{}：沒有明細（資料列是空的）", description);
			return DividendDetailResult.notFound();
		}
		JsonNode fields = root.path("fields");
		int codeIndex = DividendParsing.requireField(fields, CODE_FIELD);
		int cashIndex = DividendParsing.requireField(fields, CASH_FIELD);
		int stockIndex = DividendParsing.requireField(fields, STOCK_FIELD);
		for (JsonNode row : data) {
			// 代號尾端有空白（"2884  "），比對前先去掉
			String code = DividendParsing.stripToNull(DividendParsing.cell(row, codeIndex));
			if (stockNo.equals(code)) {
				BigDecimal cash = DividendParsing.parseDetailAmount(DividendParsing.cell(row, cashIndex));
				BigDecimal stock = DividendParsing.parseDetailAmount(DividendParsing.cell(row, stockIndex));
				return DividendDetailResult.found(new TwseDividendDetail(code, cash, stock));
			}
		}
		// 回傳的是別檔股票：測試伺服器或官方回錯資料，不能拿來用
		throw new IllegalArgumentException("回傳的代號和查詢的 " + stockNo + " 不同");
	}
}
