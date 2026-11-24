package io.hsiaopingkuo.stockholdings.quote;

import java.math.BigDecimal;
import java.time.LocalDate;

import tools.jackson.databind.JsonNode;

/**
 * 解析官方行情 JSON 的共用小工具。官方格式有不少地雷（民國年、千分位、欄位名稱夾空白），集中在這裡處理。
 */
final class QuoteParsing {

	/** 民國紀年和西元差 1911 年。 */
	private static final int ROC_YEAR_OFFSET = 1911;

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
	 * 當天沒有成交時官方顯示 "--"（或空白），代表「沒有收盤價」，回 null，而不是當成 0。
	 */
	static BigDecimal parsePrice(String price) {
		if (price == null) {
			return null;
		}
		String cleaned = price.replace(",", "").trim();
		if (cleaned.isEmpty() || "--".equals(cleaned)) {
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
	 * 取 data 最後一列（最近一個交易日）的日期和收盤價。
	 * 找不到需要的欄位時丟例外：代表官方格式變了，這份資料不可信。
	 */
	static DailyClose lastDailyClose(JsonNode fields, JsonNode data, String closeField) {
		int dateIndex = indexOfField(fields, "日期");
		int closeIndex = indexOfField(fields, closeField);
		if (dateIndex < 0 || closeIndex < 0) {
			throw new IllegalArgumentException("找不到日期或「" + closeField + "」欄位：" + fields);
		}
		JsonNode lastRow = data.get(data.size() - 1);
		return new DailyClose(parseRocDate(text(lastRow.get(dateIndex))), parsePrice(text(lastRow.get(closeIndex))));
	}

	/** 取字串值；節點不存在或不是字串時回 null（Jackson 3 的 asString() 對非字串節點行為不直覺，這裡自己判斷）。 */
	static String text(JsonNode node) {
		return node != null && node.isString() ? node.stringValue() : null;
	}

	/** 某一天的收盤資訊。closePrice 可能是 null（當天沒有成交）。 */
	record DailyClose(LocalDate date, BigDecimal closePrice) {
	}
}
