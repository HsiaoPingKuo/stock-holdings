package io.hsiaopingkuo.stockholdings.quote;

import java.time.LocalDate;

import io.hsiaopingkuo.stockholdings.entity.Market;

/**
 * 查一段期間內、單一市場全部的除權息事件（列表）。
 * 包成 interface，背景排程的測試才能換成假的實作，不必連網。
 */
public interface DividendListClient {

	Market market();

	/**
	 * @param start 開始日（含）
	 * @param end   結束日（含）
	 * @return 不會丟例外：任何連線或解析問題都轉成 FAILED 或 BLOCKED
	 */
	DividendListResult fetchRange(LocalDate start, LocalDate end);
}
