package io.hsiaopingkuo.stockholdings.service;

import java.util.List;

import io.hsiaopingkuo.stockholdings.entity.Market;

/**
 * 單一市場同步一輪除權息的摘要（第 6 題）。4c 的排程用它寫 log、決定要不要冷卻。
 *
 * @param fetchedYears    這一輪成功抓到的年份（有資料，或確定那段期間沒有除權息）
 * @param failedYears     失敗、下一輪再試的年份
 * @param eventsInserted  新增的事件數
 * @param eventsUpdated   數值真的有變的事件數
 * @param detailsQueried  送出的明細請求數（含被擋的那一個）
 * @param detailsFound    查到明細的筆數
 * @param detailsNotFound 官方說沒有明細的筆數
 * @param detailsFailed   明細失敗、隔天再試的筆數
 */
public record DividendSyncResult(Market market, Outcome outcome, List<Integer> fetchedYears,
		List<Integer> failedYears, int eventsInserted, int eventsUpdated, int detailsQueried, int detailsFound,
		int detailsNotFound, int detailsFailed) {

	public enum Outcome {
		/** 這一輪做完了（個別年份或明細失敗不影響，下一輪再試）。 */
		COMPLETED,
		/** 沒有任何交易，不需要抓，沒有發出請求。 */
		NO_TRADES,
		/** 被官方擋下（403、429、回傳 HTML），這個市場這一輪停止；冷卻多久由排程決定。 */
		BLOCKED,
		/** 等待時被中斷（伺服器關閉），停止這一輪。 */
		INTERRUPTED,
		/** 發生沒預期到的例外（例如資料庫錯誤），已記 log，停止這一輪。 */
		ERROR
	}

	public DividendSyncResult {
		fetchedYears = List.copyOf(fetchedYears);
		failedYears = List.copyOf(failedYears);
	}

	public boolean blocked() {
		return outcome == Outcome.BLOCKED;
	}
}
