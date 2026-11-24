package io.hsiaopingkuo.stockholdings.dto;

/**
 * JSON 錯誤回應，格式固定是 {"error":{"code":"...","message":"..."}}（專案慣例，App API 也會用同一個格式）。
 * code 給程式判斷，message 給人看。
 */
public record ErrorResponse(Detail error) {

	// 不叫 Error：會和 Java 內建的 java.lang.Error 撞名，閱讀時容易誤會
	public record Detail(String code, String message) {
	}

	public static ErrorResponse of(String code, String message) {
		return new ErrorResponse(new Detail(code, message));
	}
}
