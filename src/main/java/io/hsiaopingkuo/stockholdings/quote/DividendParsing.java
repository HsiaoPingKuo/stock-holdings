package io.hsiaopingkuo.stockholdings.quote;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.hsiaopingkuo.stockholdings.calc.DividendKind;
import tools.jackson.databind.JsonNode;

/**
 * 解析官方除權息 JSON 的小工具。行情共用的部分（民國 "115/11/23"、千分位、欄位名稱空白）在 QuoteParsing，
 * 這裡只放除權息才有的格式：中文民國日期、帶單位的明細數字、種類文字、明細參數。
 */
final class DividendParsing {

	/** 民國紀年和西元差 1911 年。 */
	private static final int ROC_YEAR_OFFSET = 1911;
	/** 上市列表的日期，例如「114年06月12日」；月、日也接受一位數。 */
	private static final Pattern ROC_CHINESE_DATE = Pattern.compile("(\\d{2,3})年(\\d{1,2})月(\\d{1,2})日");
	/**
	 * 明細的數字，例如「1.2 元／股」「10 股」。單位可以省略；斜線全形、半形都接受。
	 * 不接受負號：股利和配股不會是負的，出現了代表格式不對。
	 */
	private static final Pattern DETAIL_AMOUNT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:元[／/]股|股)?");
	/** 「詳細資料」欄位，例如「2884,20250722」。 */
	private static final Pattern DETAIL_PARAM = Pattern.compile("([0-9A-Za-z]+),(\\d{8})");
	private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

	private DividendParsing() {
	}

	/** 「114年06月12日」→ 2025-06-12。格式不對或日期不存在（13 月）時丟例外。 */
	static LocalDate parseRocChineseDate(String rocDate) {
		if (rocDate == null) {
			throw new IllegalArgumentException("沒有日期");
		}
		Matcher matcher = ROC_CHINESE_DATE.matcher(rocDate.strip());
		if (!matcher.matches()) {
			throw new IllegalArgumentException("不是民國日期格式：" + rocDate);
		}
		try {
			return LocalDate.of(Integer.parseInt(matcher.group(1)) + ROC_YEAR_OFFSET,
					Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
		} catch (DateTimeException e) {
			throw new IllegalArgumentException("日期不存在：" + rocDate, e);
		}
	}

	/**
	 * 種類文字 → DividendKind。上市寫「息／權／權息」，上櫃多一個「除」字。
	 * 不認得的種類丟例外：不能自己猜成某一種，否則股利會算錯。
	 */
	static DividendKind parseKind(String kind) {
		String value = kind == null ? "" : kind.strip();
		return switch (value) {
			case "息", "除息" -> DividendKind.CASH;
			case "權", "除權" -> DividendKind.STOCK;
			case "權息", "除權息" -> DividendKind.CASH_AND_STOCK;
			default -> throw new IllegalArgumentException("不認得的除權息種類：" + kind);
		};
	}

	/**
	 * 明細的數字：「1.2 元／股」→ 1.2、「18,000,000 股」→ 18000000、空字串 → 0（官方沒填就是沒有）。
	 * 保留原本的小數位數（4.50002 不會變成 4.500020）。看不懂時丟例外，整筆明細當成失敗。
	 */
	static BigDecimal parseDetailAmount(String amount) {
		if (amount == null) {
			throw new IllegalArgumentException("沒有數字");
		}
		String cleaned = amount.replace(",", "").strip();
		if (cleaned.isEmpty()) {
			return BigDecimal.ZERO;
		}
		Matcher matcher = DETAIL_AMOUNT.matcher(cleaned);
		if (!matcher.matches()) {
			throw new IllegalArgumentException("看不懂的明細數字：" + amount);
		}
		return new BigDecimal(matcher.group(1));
	}

	/**
	 * 列表上的價格：去千分位；空白、"--"、看不懂時回 null。
	 * 單一欄位看不懂不讓整份失敗：事件保留，由 DividendSourceRule 判成「無法取得」。
	 */
	static BigDecimal parseNumberOrNull(String number) {
		try {
			return QuoteParsing.parsePrice(number);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** 「2884,20250722」→ 代號和日期；空白或格式不對回 null（代表沒辦法查明細，不影響列表本身）。 */
	static DetailParam parseDetailParam(String param) {
		if (param == null) {
			return null;
		}
		Matcher matcher = DETAIL_PARAM.matcher(param.strip());
		if (!matcher.matches()) {
			return null;
		}
		try {
			return new DetailParam(matcher.group(1), LocalDate.parse(matcher.group(2), BASIC_DATE));
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	/** 欄位名稱的位置；找不到丟例外（官方格式變了，整份資料不可信）。 */
	static int requireField(JsonNode fields, String wanted) {
		int index = QuoteParsing.indexOfField(fields, wanted);
		if (index < 0) {
			throw new IllegalArgumentException("找不到「" + wanted + "」欄位：" + fields);
		}
		return index;
	}

	/** 一列裡某個位置的字串；這一列比較短或不是字串時回 null。 */
	static String cell(JsonNode row, int index) {
		return QuoteParsing.text(row.get(index));
	}

	/** 去掉前後空白；null 或全是空白時回 null。 */
	static String stripToNull(String value) {
		if (value == null) {
			return null;
		}
		String stripped = value.strip();
		return stripped.isEmpty() ? null : stripped;
	}

	/** 上市明細 API 的查詢參數。 */
	record DetailParam(String stockNo, LocalDate date) {
	}
}
