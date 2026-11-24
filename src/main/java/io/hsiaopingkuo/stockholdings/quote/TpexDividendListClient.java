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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 櫃買中心「除權除息計算結果表」（上櫃列表，題目提供的 API）：
 * {TPEX_BASE_URL}/www/zh-tw/bulletin/exDailyQ?startDate=yyyy/MM/dd&endDate=yyyy/MM/dd&response=json
 * 資料在 tables[0].data，日期是「114/07/24」，名稱尾端有空白。
 * 跟上市不同，這張表直接有「現金股利」「每仟股無償配股」，不用再查明細。
 */
public class TpexDividendListClient implements DividendListClient {

	private static final Logger log = LoggerFactory.getLogger(TpexDividendListClient.class);

	static final String PATH = "/www/zh-tw/bulletin/exDailyQ";
	// 日期用斜線，和官方範例一致
	private static final DateTimeFormatter DATE_PARAM = DateTimeFormatter.ofPattern("yyyy/MM/dd");

	private final RestClient restClient;
	private final JsonMapper jsonMapper;

	public TpexDividendListClient(RestClient restClient, JsonMapper jsonMapper) {
		this.restClient = restClient;
		this.jsonMapper = jsonMapper;
	}

	@Override
	public Market market() {
		return Market.TPEX;
	}

	@Override
	public DividendListResult fetchRange(LocalDate start, LocalDate end) {
		String description = "櫃買 exDailyQ " + start + "～" + end;
		// 用 queryParam 組網址：範本變數裡的 / 會被編碼成 %2F，跟官方範例不同
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
			// 沒有 tables、缺欄位、某一列的日期或種類看不懂：官方格式可能改了，整份不可信，之後重試
			log.warn("{}：格式和預期不同：{}", description, e.getMessage());
			return DividendListResult.failed();
		}
	}

	private DividendListResult parse(JsonNode root, String description) {
		String stat = QuoteParsing.text(root.path("stat"));
		if (!QuoteParsing.isOk(stat)) {
			// 實測沒有資料時 stat 仍是 ok；不是 ok 代表別的狀況，不能當成「沒有資料」
			log.warn("{}：無法辨識的 stat：{}", description, stat);
			return DividendListResult.failed();
		}
		JsonNode tables = root.path("tables");
		if (!tables.isArray() || tables.isEmpty()) {
			throw new IllegalArgumentException("沒有 tables");
		}
		JsonNode table = tables.get(0);
		JsonNode data = table.path("data");
		if (!data.isArray()) {
			throw new IllegalArgumentException("tables[0] 沒有 data");
		}
		if (data.isEmpty()) {
			log.info("{}：這段期間沒有除權息（資料列是空的）", description);
			return DividendListResult.noData();
		}
		JsonNode fields = table.path("fields");
		int dateIndex = DividendParsing.requireField(fields, "除權息日期");
		int codeIndex = DividendParsing.requireField(fields, "代號");
		int nameIndex = DividendParsing.requireField(fields, "名稱");
		int prevCloseIndex = DividendParsing.requireField(fields, "除權息前收盤價");
		int referenceIndex = DividendParsing.requireField(fields, "除權息參考價");
		int rightsPlusDividendIndex = DividendParsing.requireField(fields, "權值+息值");
		int kindIndex = DividendParsing.requireField(fields, "權/息");
		int dividendReferenceIndex = DividendParsing.requireField(fields, "減除股利參考價");
		int cashIndex = DividendParsing.requireField(fields, "現金股利");
		int stockIndex = DividendParsing.requireField(fields, "每仟股無償配股");

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
				exDate = QuoteParsing.parseRocDate(rawDate);
				kind = DividendParsing.parseKind(DividendParsing.cell(row, kindIndex));
			} catch (RuntimeException e) {
				// 略過這一列的話，這次除權息會永遠消失；寧可整份失敗、之後重抓。log 寫出是哪一列方便追查
				throw new IllegalArgumentException(
						"第 " + (i + 1) + " 列（代號 " + code + "、日期 " + rawDate + "）：" + e.getMessage(), e);
			}
			rows.add(new DividendListRow(Market.TPEX, exDate, code,
					DividendParsing.stripToNull(DividendParsing.cell(row, nameIndex)), kind,
					DividendParsing.parseNumberOrNull(DividendParsing.cell(row, prevCloseIndex)),
					DividendParsing.parseNumberOrNull(DividendParsing.cell(row, referenceIndex)),
					DividendParsing.parseNumberOrNull(DividendParsing.cell(row, rightsPlusDividendIndex)),
					DividendParsing.parseNumberOrNull(DividendParsing.cell(row, dividendReferenceIndex)),
					DividendParsing.parseNumberOrNull(DividendParsing.cell(row, cashIndex)),
					DividendParsing.parseNumberOrNull(DividendParsing.cell(row, stockIndex)),
					null, null));
		}
		return DividendListResult.ok(rows);
	}
}
