package io.hsiaopingkuo.stockholdings.exception;

/**
 * 同一個冪等鍵的第一個請求還在處理中，又收到第二個（第 6 題決定 6-A）。
 * 第一個的結果還不知道（可能成功、也可能失敗回滾），所以不處理也不猜，請用戶端稍後用同一個鍵再送。
 * 第 6 階段的 API 回 409 REQUEST_IN_PROGRESS。
 */
public class RequestInProgressException extends RuntimeException {

	public RequestInProgressException() {
		super("這個操作還在處理中，請稍後再試");
	}
}
