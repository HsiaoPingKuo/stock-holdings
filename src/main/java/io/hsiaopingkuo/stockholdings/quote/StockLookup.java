package io.hsiaopingkuo.stockholdings.quote;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.hsiaopingkuo.stockholdings.entity.Market;

/**
 * 查一個市場、一個代號的結果。
 * 「查不到」和「查不了」要分開：前者代表代號在這個市場不存在，後者代表官方網站沒回應或回了看不懂的東西，
 * 兩者合併後給使用者的訊息完全不同（查無此代號 vs. 請稍後再試）。
 *
 * @param status     FOUND／NOT_FOUND／UNAVAILABLE
 * @param market     查的是哪個市場
 * @param name       股票名稱；只有 FOUND 時才可能有值（解析不出來時是 null）
 * @param tradeDate  最近一個交易日（最後一列資料的日期，已轉成西元），不論這檔當天有沒有成交
 * @param closePrice 最近一個「有成交」日的收盤價；查到的範圍內都沒有成交（官方全是 "--"）時是 null
 * @param closeDate  closePrice 是哪一天；當天沒成交時會比 tradeDate 早
 */
public record StockLookup(Status status, Market market, String name, LocalDate tradeDate, BigDecimal closePrice,
		LocalDate closeDate) {

	public enum Status {
		/** 回應正常，而且有資料列。 */
		FOUND,
		/** 回應正常，但沒有資料（代號在這個市場不存在，或這個月還沒有交易日）。 */
		NOT_FOUND,
		/** 逾時、HTTP 錯誤、JSON 解析失敗、格式和預期不同。 */
		UNAVAILABLE,
		/**
		 * 被官方擋下（HTTP 403、429，或回傳 HTML）。對新增持股來說和 UNAVAILABLE 一樣是「無法確認」；
		 * 排程看到它要停止這一輪並冷卻，所以要分開。
		 */
		BLOCKED
	}

	/** 最後一個交易日就有成交：收盤日就是交易日。 */
	public static StockLookup found(Market market, String name, LocalDate tradeDate, BigDecimal closePrice) {
		return found(market, name, tradeDate, closePrice, closePrice == null ? null : tradeDate);
	}

	public static StockLookup found(Market market, String name, LocalDate tradeDate, BigDecimal closePrice,
			LocalDate closeDate) {
		return new StockLookup(Status.FOUND, market, name, tradeDate, closePrice, closeDate);
	}

	public static StockLookup notFound(Market market) {
		return new StockLookup(Status.NOT_FOUND, market, null, null, null, null);
	}

	public static StockLookup unavailable(Market market) {
		return new StockLookup(Status.UNAVAILABLE, market, null, null, null, null);
	}

	public static StockLookup blocked(Market market) {
		return new StockLookup(Status.BLOCKED, market, null, null, null, null);
	}

	/** 把 HTTP 失敗轉成查詢結果：被擋 → BLOCKED，其他 → UNAVAILABLE。 */
	static StockLookup failure(Market market, QuoteHttp.Outcome outcome) {
		return outcome == QuoteHttp.Outcome.BLOCKED ? blocked(market) : unavailable(market);
	}

	/** 轉成要寫進 quote 表的資料；只有 FOUND 時才有意義。 */
	public DailyQuote toDailyQuote(String code) {
		return new DailyQuote(code, name, market, tradeDate, closePrice, closeDate);
	}
}
