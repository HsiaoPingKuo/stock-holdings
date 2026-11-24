package io.hsiaopingkuo.stockholdings.quote;

import java.util.List;

/**
 * 查一次除權息列表的結果。
 * - OK：有資料
 * - NO_DATA：官方回應正常，但這段期間沒有除權息（不是錯誤）
 * - FAILED：HTTP 錯誤、逾時、JSON 壞掉、格式和預期不同、不認得的 stat → 之後重試
 * - BLOCKED：被官方擋下（403、429、回傳 HTML）→ 這個市場要暫停一段時間（第 6 題設計決定 7-A）
 *
 * @param rows 只有 OK 時有內容
 */
public record DividendListResult(Status status, List<DividendListRow> rows) {

	public enum Status {
		OK, NO_DATA, FAILED, BLOCKED
	}

	public static DividendListResult ok(List<DividendListRow> rows) {
		return new DividendListResult(Status.OK, List.copyOf(rows));
	}

	public static DividendListResult noData() {
		return new DividendListResult(Status.NO_DATA, List.of());
	}

	public static DividendListResult failed() {
		return new DividendListResult(Status.FAILED, List.of());
	}

	public static DividendListResult blocked() {
		return new DividendListResult(Status.BLOCKED, List.of());
	}

	/** 被擋也算失敗：兩種都沒拿到資料，差別只在要不要暫停。 */
	public boolean isFailure() {
		return status == Status.FAILED || status == Status.BLOCKED;
	}

	/** 把 HTTP 失敗轉成結果：被擋 → BLOCKED，其他 → FAILED。 */
	static DividendListResult failure(QuoteHttp.Outcome outcome) {
		return outcome == QuoteHttp.Outcome.BLOCKED ? blocked() : failed();
	}
}
