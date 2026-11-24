package io.hsiaopingkuo.stockholdings.exception;

/**
 * 同一個冪等鍵被用在不同的請求（API 或內容不同，第 6 題決定 6-A）。
 * 用戶端應該為新的操作產生新的鍵；照舊的結果回傳會讓使用者以為這次的內容已經寫入。
 * 第 6 階段的 API 回 422 IDEMPOTENCY_KEY_REUSED。
 */
public class IdempotencyKeyReusedException extends RuntimeException {

	public IdempotencyKeyReusedException() {
		super("這個 Idempotency-Key 已經用在另一個請求，請為新的操作產生新的鍵");
	}
}
