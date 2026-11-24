package io.hsiaopingkuo.stockholdings.exception;

/**
 * /api/v2 的寫入沒帶 Idempotency-Key，或格式不對（空白、超過 64 字元，V6 欄位的長度）。
 * API 回 400 IDEMPOTENCY_KEY_REQUIRED：和一般的欄位錯誤分開，App 開發者一看就知道是程式漏帶 header。
 */
public class IdempotencyKeyRequiredException extends RuntimeException {

	public IdempotencyKeyRequiredException(String message) {
		super(message);
	}
}
