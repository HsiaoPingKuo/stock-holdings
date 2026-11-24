package io.hsiaopingkuo.stockholdings.quote;

import java.time.LocalDate;

import io.hsiaopingkuo.stockholdings.entity.Market;

/**
 * 一次取得單一市場某一天「全部股票」的收盤行情（批次）。
 * 包成 interface，排程的測試才能換成假的實作，不必連網。
 */
public interface MarketCloseBatchClient {

	Market market();

	/**
	 * @param date 要查的交易日（台北時間）
	 * @return 不會丟例外：任何連線或解析問題都轉成 FAILED 或 BLOCKED
	 */
	BatchQuoteResult fetchDay(LocalDate date);
}
