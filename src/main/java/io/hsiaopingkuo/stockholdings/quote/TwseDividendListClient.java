package io.hsiaopingkuo.stockholdings.quote;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import io.hsiaopingkuo.stockholdings.calc.DividendKind;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.quote.DividendParsing.DetailParam;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 證交所「除權除息計算結果表」（上市列表，題目提供的 API）：
 * {TWSE_BASE_URL}/rwd/zh/exRight/TWT49U?startDate=yyyyMMdd&endDate=yyyyMMdd&response=json
 * 資料在最外層 data，日期是「114年06月12日」。這張表沒有每股現金股利、每千股配股，
 * 只有「權值+息值」，要再查明細或用 DividendSourceRule 判斷。
 */
public class TwseDividendListClient implements DividendListClient {

	private static final Logger log = LoggerFactory.getLogger(TwseDividendListClient.class);

	static final String PATH = "/rwd/zh/exRight/TWT49U";
	private static final DateTimeFormatter DATE_PARAM = DateTimeFormatter.ofPattern("yyyyMMdd");
	/** 期間內沒有除權息時，stat 是「很抱歉，沒有符合條件的資料!」，而且沒有 data。 */
	private static final String NO_DATA_MESSAGE = "沒有符合條件的資料";

	private final RestClient restClient;
	private final JsonMapper jsonMapper;

	public TwseDividendListClient(RestClient restClient, JsonMapper jsonMapper) {
		this.restClient = restClient;
		this.jsonMapper = jsonMapper;
	}

	@Override
	public Market market() {
		return Market.TWSE;
	}

	@Override
	public DividendListResult fetchRange(LocalDate start, LocalDate end) {
		String description = "證交所 TWT49U " + start + "～" + end;
		QuoteHttp.Response response = QuoteHttp.getJson(restClient, jsonMapper, uriBuilder -> uriBuilder
				.path(PATH)
				.queryParam("startDate", start.format(DATE_PARAM))
				.queryParam("endDate", end.format(DATE_PARAM))
				.queryParam("response", "json")
				.build(), description);
		if (response.outcome() != QuoteHttp.Outcome.OK) {
			return DividendListResult.failure(response.outcome());
		}
		try {
			return parse(response.json(), description);
		} catch (RuntimeException e) {
			// 缺欄位、某一列的日期或種類看不懂：官方格式可能改了，整份不可信，之後重試
			log.warn("{}：格式和預期不同：{}", description, e.getMessage());
			return DividendListResult.failed();
		}
	}

	private DividendListResult parse(JsonNode root, String description) {
		String stat = QuoteParsing.text(root.path("stat"));
		if (!QuoteParsing.isOk(stat)) {
			if (stat != null && stat.contains(NO_DATA_MESSAGE)) {
				log.info("{}：這段期間沒有除權息", description);
				return DividendListResult.noData();
			}
			// 其他 stat（例如查詢太頻繁的提示）不能當成「沒有資料」，否則這段期間會被當成抓完、不再重抓
			log.warn("{}：無法辨識的 stat：{}", description, stat);
			return DividendListResult.failed();
		}
		JsonNode data = root.path("data");
		if (!data.isArray()) {
			throw new IllegalArgumentException("stat 是 OK 但沒有 data");
		}
		if (data.isEmpty()) {
			log.info("{}：這段期間沒有除權息（資料列是空的）", description);
			return DividendListResult.noData();
		}
		JsonNode fields = root.path("fields");
		int dateIndex = DividendParsing.requireField(fields, "資料日期");
		int codeIndex = DividendParsing.requireField(fields, "股票代號");
		int nameIndex = DividendParsing.requireField(fields, "股票名稱");
		int prevCloseIndex = DividendParsing.requireField(fields, "除權息前收盤價");
		int referenceIndex = DividendParsing.requireField(fields, "除權息參考價");
		int rightsPlusDividendIndex = DividendParsing.requireField(fields, "權值+息值");
		int kindIndex = DividendParsing.requireField(fields, "權/息");
		int dividendReferenceIndex = DividendParsing.requireField(fields, "減除股利參考價");
		int detailIndex = DividendParsing.requireField(fields, "詳細資料");

		List<DividendListRow> rows = new ArrayList<>();
		for (int i = 0; i < data.size(); i++) {
			JsonNode row = data.get(i);
			String code = DividendParsing.stripToNull(DividendParsing.cell(row, codeIndex));
			String rawDate = DividendParsing.cell(row, dateIndex);
			LocalDate exDate;
			DividendKind kind;
			try {
				if (code == null) {
					throw new IllegalArgumentException("沒有代號");
				}
				exDate = DividendParsing.parseRocChineseDate(rawDate);
				kind = DividendParsing.parseKind(DividendParsing.cell(row, kindIndex));
			} catch (RuntimeException e) {
				// 略過這一列的話，這次除權息會永遠消失；寧可整份失敗、之後重抓。log 寫出是哪一列方便追查
				throw new IllegalArgumentException(
						"第 " + (i + 1) + " 列（代號 " + code + "、日期 " + rawDate + "）：" + e.getMessage(), e);
			}
			DetailParam detail = DividendParsing.parseDetailParam(DividendParsing.cell(row, detailIndex));
			rows.add(new DividendListRow(Market.TWSE, exDate, code,
					DividendParsing.stripToNull(DividendParsing.cell(row, nameIndex)), kind,
					DividendParsing.parseNumberOrNull(DividendParsing.cell(row, prevCloseIndex)),
					DividendParsing.parseNumberOrNull(DividendParsing.cell(row, referenceIndex)),
					DividendParsing.parseNumberOrNull(DividendParsing.cell(row, rightsPlusDividendIndex)),
					DividendParsing.parseNumberOrNull(DividendParsing.cell(row, dividendReferenceIndex)),
					null, null,
					detail == null ? null : detail.stockNo(),
					detail == null ? null : detail.date()));
		}
		return DividendListResult.ok(rows);
	}
}
