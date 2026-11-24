package io.hsiaopingkuo.stockholdings.exception;

/**
 * 新增的代號已經在持股中。
 * 訊息格式和 App 1.0 API 規格的 DUPLICATE_CODE 一致（「2330 已在持股中」），網頁和 API 可以共用。
 */
public class DuplicateCodeException extends RuntimeException {

	private final String code;

	public DuplicateCodeException(String code) {
		super(code + " 已在持股中");
		this.code = code;
	}

	public String getCode() {
		return code;
	}
}
