package io.hsiaopingkuo.stockholdings.exception;

/**
 * 交易的輸入不符合規則（第 6 題決定 9：日期、股數、價格）。
 * API 回 400 VALIDATION_ERROR；網頁依 field 把訊息顯示在對應的欄位旁邊。
 */
public class TradeValidationException extends RuntimeException {

	// 哪一個欄位有錯：code、date、shares、price（對應表單與 API 的欄位）
	private final String field;

	public TradeValidationException(String field, String message) {
		super(message);
		this.field = field;
	}

	public String getField() {
		return field;
	}
}
