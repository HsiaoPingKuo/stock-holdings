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
 * @param tradeDate  最近一個交易日（資料列本身的日期，已轉成西元）
 * @param closePrice 該日收盤價；當天沒有成交（官方顯示 "--"）時是 null
 */
public record StockLookup(Status status, Market market, String name, LocalDate tradeDate, BigDecimal closePrice) {

	public enum Status {
		/** 回應正常，而且有資料列。 */
		FOUND,
		/** 回應正常，但沒有資料（代號在這個市場不存在，或這個月還沒有交易日）。 */
		NOT_FOUND,
		/** 逾時、HTTP 錯誤、回傳 HTML、JSON 解析失敗、格式和預期不同。 */
		UNAVAILABLE
	}

	public static StockLookup found(Market market, String name, LocalDate tradeDate, BigDecimal closePrice) {
		return new StockLookup(Status.FOUND, market, name, tradeDate, closePrice);
	}

	public static StockLookup notFound(Market market) {
		return new StockLookup(Status.NOT_FOUND, market, null, null, null);
	}

	public static StockLookup unavailable(Market market) {
		return new StockLookup(Status.UNAVAILABLE, market, null, null, null);
	}
}
