package io.hsiaopingkuo.stockholdings.quote;

/**
 * 查一次上市除權息明細的結果。
 * - FOUND：查到明細
 * - NOT_FOUND：官方回應正常，但沒有這筆明細（不是錯誤）→ 改用列表的值判斷（第 6 題設計決定 7-C）
 * - FAILED：HTTP 錯誤、逾時、JSON 壞掉、格式和預期不同、不認得的 stat → 之後重試
 * - BLOCKED：被官方擋下（403、429、回傳 HTML）→ 要暫停一段時間
 *
 * @param detail 只有 FOUND 時有值
 */
public record DividendDetailResult(Status status, TwseDividendDetail detail) {

	public enum Status {
		FOUND, NOT_FOUND, FAILED, BLOCKED
	}

	public static DividendDetailResult found(TwseDividendDetail detail) {
		return new DividendDetailResult(Status.FOUND, detail);
	}

	public static DividendDetailResult notFound() {
		return new DividendDetailResult(Status.NOT_FOUND, null);
	}

	public static DividendDetailResult failed() {
		return new DividendDetailResult(Status.FAILED, null);
	}

	public static DividendDetailResult blocked() {
		return new DividendDetailResult(Status.BLOCKED, null);
	}

	/** 被擋也算失敗：兩種都沒拿到資料，差別只在要不要暫停。 */
	public boolean isFailure() {
		return status == Status.FAILED || status == Status.BLOCKED;
	}

	static DividendDetailResult failure(QuoteHttp.Outcome outcome) {
		return outcome == QuoteHttp.Outcome.BLOCKED ? blocked() : failed();
	}
}
