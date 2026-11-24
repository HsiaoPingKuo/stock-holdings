package io.hsiaopingkuo.stockholdings.quote;

import java.time.LocalDate;

import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.quote.StockLookup.Status;

/**
 * 查詢單一市場「某代號某個月」的逐檔成交資訊。
 * 包成 interface，MarketResolver 測試時就能換成假的實作，不必連網。
 */
public interface StockLookupClient {

	Market market();

	/**
	 * @param code       已正規化（trim、大寫）的股票代號
	 * @param dayInMonth 要查的月份裡的任一天；官方 API 會回整個月的資料
	 * @return 不會丟例外：任何連線或解析問題都回 UNAVAILABLE
	 */
	StockLookup lookupMonth(String code, LocalDate dayInMonth);

	/**
	 * 查這個代號「目前最新」的資料：先查這個月，必要時再查上個月。
	 * 月初有兩個陷阱，都要往上個月找：
	 * 1. 這個月還沒有交易日：證交所回的和「代號不存在」是同一句話（NOT_FOUND）
	 * 2. 這個月有交易日，但每一天都沒成交（全是 "--"）：收盤價要用上個月最後一個有成交的
	 * 第 2 種情況的最近交易日仍以這個月為準，只借上個月的收盤價和收盤日。
	 *
	 * @param today 台北時間的今天
	 */
	default StockLookup lookupLatest(String code, LocalDate today) {
		StockLookup thisMonth = lookupMonth(code, today);
		if (thisMonth.status() == Status.NOT_FOUND) {
			return lookupMonth(code, today.minusMonths(1));
		}
		if (thisMonth.status() == Status.FOUND && thisMonth.closePrice() == null) {
			StockLookup lastMonth = lookupMonth(code, today.minusMonths(1));
			if (lastMonth.status() == Status.FOUND && lastMonth.closePrice() != null) {
				String name = thisMonth.name() != null ? thisMonth.name() : lastMonth.name();
				return StockLookup.found(market(), name, thisMonth.tradeDate(), lastMonth.closePrice(),
						lastMonth.closeDate());
			}
			// 上個月也沒成交或查不了：仍回這個月的結果（收盤價是 null），至少市場、名稱、最近交易日是對的
		}
		return thisMonth;
	}
}
