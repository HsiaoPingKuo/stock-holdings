package io.hsiaopingkuo.stockholdings.entity;

/** 冪等鍵紀錄的狀態，存名稱，對應 ck_idempotency_record_status。 */
public enum IdempotencyStatus {
	/** 寫入中：只存在於還沒 commit 的資料庫交易裡 */
	IN_PROGRESS,
	/** 完成：已存下回應，重送時原樣回傳 */
	COMPLETED
}
