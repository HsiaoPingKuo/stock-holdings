package io.hsiaopingkuo.stockholdings.quote;

import java.time.LocalDate;

import io.hsiaopingkuo.stockholdings.entity.Market;

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
}
