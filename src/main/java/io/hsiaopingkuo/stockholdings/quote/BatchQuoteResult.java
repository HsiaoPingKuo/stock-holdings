package io.hsiaopingkuo.stockholdings.quote;

import java.time.LocalDate;
import java.util.List;

/**
 * 批次查詢（全市場收盤行情）一次的結果。四種狀態對應排程的四種處理方式：
 * - OK：寫入 quote 表
 * - NO_DATA：官方回應正常但這一天沒有資料（還沒公布、休市），稍後再試，不算錯誤
 * - FAILED：HTTP 錯誤（含 404）、逾時、找不到收盤行情表、格式不符 → 改用逐檔查詢
 * - BLOCKED：被擋（403、429、回傳 HTML）→ 這個市場冷卻一段時間
 *
 * @param tradeDate 資料本身的交易日（只有 OK 時有值）
 * @param quotes    每一檔的收盤資料（只有 OK 時有內容）
 */
public record BatchQuoteResult(Status status, LocalDate tradeDate, List<DailyQuote> quotes) {

	public enum Status {
		OK, NO_DATA, FAILED, BLOCKED
	}

	public static BatchQuoteResult ok(LocalDate tradeDate, List<DailyQuote> quotes) {
		return new BatchQuoteResult(Status.OK, tradeDate, List.copyOf(quotes));
	}

	public static BatchQuoteResult noData() {
		return new BatchQuoteResult(Status.NO_DATA, null, List.of());
	}

	public static BatchQuoteResult failed() {
		return new BatchQuoteResult(Status.FAILED, null, List.of());
	}

	public static BatchQuoteResult blocked() {
		return new BatchQuoteResult(Status.BLOCKED, null, List.of());
	}

	/** 把 HTTP 失敗轉成批次結果：被擋 → BLOCKED，其他 → FAILED。 */
	static BatchQuoteResult failure(QuoteHttp.Outcome outcome) {
		return outcome == QuoteHttp.Outcome.BLOCKED ? blocked() : failed();
	}
}
