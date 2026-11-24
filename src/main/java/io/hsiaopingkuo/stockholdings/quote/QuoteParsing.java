package io.hsiaopingkuo.stockholdings.quote;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import io.hsiaopingkuo.stockholdings.entity.Market;
import tools.jackson.databind.JsonNode;

/**
 * 解析官方行情 JSON 的共用小工具。官方格式有不少地雷（民國年、千分位、欄位名稱夾空白），集中在這裡處理。
 */
final class QuoteParsing {

	/** 民國紀年和西元差 1911 年。 */
	private static final int ROC_YEAR_OFFSET = 1911;
	/** 對應 quote 表的 code VARCHAR(10)、name VARCHAR(50)。 */
	private static final int MAX_CODE_LENGTH = 10;
	private static final int MAX_NAME_LENGTH = 50;

	private QuoteParsing() {
	}

	/** stat 比對不分大小寫：證交所回 "OK"、櫃買回 "ok"。 */
	static boolean isOk(String stat) {
		return stat != null && "ok".equalsIgnoreCase(stat.trim());
	}

	/** 去掉所有空白（含全形空白），讓「日 期」「收盤 」和「日期」「收盤」比得起來。 */
	static String normalizeField(String field) {
		return field == null ? "" : field.replaceAll("[\\s\\u3000]+", "");
	}

	/** 民國日期 "115/11/23" → 西元 2026-11-23。格式不對時丟例外，由呼叫端當成 UNAVAILABLE。 */
	static LocalDate parseRocDate(String rocDate) {
		String[] parts = rocDate.trim().split("/");
		if (parts.length != 3) {
			throw new IllegalArgumentException("不是民國日期格式：" + rocDate);
		}
		int year = Integer.parseInt(parts[0].trim()) + ROC_YEAR_OFFSET;
		return LocalDate.of(year, Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
	}

	/**
	 * 價格字串 "2,030.00" → 2030.00。
	 * 當天沒有成交時官方顯示一串減號（或空白），代表「沒有收盤價」，回 null，而不是當成 0。
	 * 減號個數不統一：證交所是 "--"，櫃買 otc 批次實測是 "----"，所以只要全部都是減號就算。
	 */
	static BigDecimal parsePrice(String price) {
		if (price == null) {
			return null;
		}
		String cleaned = price.replace(",", "").trim();
		if (cleaned.isEmpty() || cleaned.matches("-+")) {
			return null;
		}
		return new BigDecimal(cleaned);
	}

	/** 欄位名稱在 fields 陣列中的位置；找不到回 -1。不寫死索引，官方調整欄位順序時才不會讀錯欄。 */
	static int indexOfField(JsonNode fields, String wanted) {
		String target = normalizeField(wanted);
		for (int i = 0; i < fields.size(); i++) {
			if (target.equals(normalizeField(text(fields.get(i))))) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * 整理逐檔 API 回傳的一整個月：
	 * - 最近交易日 = 最後一列的日期（不論當天有沒有成交）
	 * - 收盤價 = 從最後一列往前找，第一個不是 "--" 的收盤價和它的日期
	 * 這個月每一列都是 "--" 時，收盤價和收盤日都是 null，由呼叫端決定要不要再查上個月。
	 * 找不到需要的欄位時丟例外：代表官方格式變了，這份資料不可信。
	 */
	static MonthSummary summarizeMonth(JsonNode fields, JsonNode data, String closeField) {
		int dateIndex = indexOfField(fields, "日期");
		int closeIndex = indexOfField(fields, closeField);
		if (dateIndex < 0 || closeIndex < 0) {
			throw new IllegalArgumentException("找不到日期或「" + closeField + "」欄位：" + fields);
		}
		LocalDate lastTradeDate = parseRocDate(text(data.get(data.size() - 1).get(dateIndex)));
		for (int i = data.size() - 1; i >= 0; i--) {
			JsonNode row = data.get(i);
			BigDecimal close = parsePrice(text(row.get(closeIndex)));
			if (close != null) {
				return new MonthSummary(lastTradeDate, close, parseRocDate(text(row.get(dateIndex))));
			}
		}
		return new MonthSummary(lastTradeDate, null, null);
	}

	/**
	 * 把批次行情表的每一列轉成 DailyQuote（整張表都是同一天 tradeDate 的資料）。
	 * 找不到代號、名稱、收盤欄位時丟例外（官方格式變了，整批不可信）；
	 * 個別一列有問題（代號空白或太長、價格看不懂）只略過那一列，不讓整批寫入失敗。
	 */
	static List<DailyQuote> parseDailyRows(JsonNode fields, JsonNode data, String codeField, String nameField,
			String closeField, Market market, LocalDate tradeDate) {
		int codeIndex = indexOfField(fields, codeField);
		int nameIndex = indexOfField(fields, nameField);
		int closeIndex = indexOfField(fields, closeField);
		if (codeIndex < 0 || nameIndex < 0 || closeIndex < 0) {
			throw new IllegalArgumentException("找不到「" + codeField + "／" + nameField + "／" + closeField + "」欄位：" + fields);
		}
		List<DailyQuote> quotes = new ArrayList<>();
		for (JsonNode row : data) {
			String code = text(row.get(codeIndex));
			code = code == null ? "" : code.strip();
			// 資料庫的代號欄位是 VARCHAR(10)；超過的不是一般股票，存進去反而會讓整批失敗
			if (code.isEmpty() || code.length() > MAX_CODE_LENGTH) {
				continue;
			}
			BigDecimal close;
			try {
				close = parsePrice(text(row.get(closeIndex)));
			} catch (NumberFormatException e) {
				// 看不懂的價格只略過這一列（等同「不在這次批次裡」，表裡原本的資料不變）
				continue;
			}
			quotes.add(DailyQuote.ofDay(code, limitName(text(row.get(nameIndex))), market, tradeDate, close));
		}
		return quotes;
	}

	/** 名稱去掉空白；超過資料庫欄位長度（50）就截斷，避免一個長名稱讓整批寫入失敗。 */
	private static String limitName(String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		String stripped = name.strip();
		return stripped.length() > MAX_NAME_LENGTH ? stripped.substring(0, MAX_NAME_LENGTH) : stripped;
	}

	/** 取字串值；節點不存在或不是字串時回 null（Jackson 3 的 asString() 對非字串節點行為不直覺，這裡自己判斷）。 */
	static String text(JsonNode node) {
		return node != null && node.isString() ? node.stringValue() : null;
	}

	/**
	 * 一個月的逐檔資料整理後的結果。
	 *
	 * @param lastTradeDate 最後一列的日期（最近交易日）
	 * @param closePrice    最近一個有成交日的收盤價；整個月都沒成交時是 null
	 * @param closeDate     closePrice 是哪一天
	 */
	record MonthSummary(LocalDate lastTradeDate, BigDecimal closePrice, LocalDate closeDate) {
	}
}
