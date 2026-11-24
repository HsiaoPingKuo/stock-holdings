package io.hsiaopingkuo.stockholdings.api;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import io.hsiaopingkuo.stockholdings.dto.ErrorResponse;

/**
 * App API 共用的回應格式。
 * 規格寫「回應格式是 JSON（UTF-8）」：Spring 預設的 Content-Type 只有 application/json、不帶 charset，
 * 有些客戶端（例如 Windows PowerShell 5.1）沒看到 charset 會用錯編碼，中文變亂碼，所以明確寫出 UTF-8。
 */
final class ApiJson {

	static final String JSON_UTF8_VALUE = "application/json;charset=UTF-8";
	static final MediaType JSON_UTF8 = MediaType.parseMediaType(JSON_UTF8_VALUE);

	private ApiJson() {
	}

	/** 規格的錯誤格式：{"error":{"code":"...","message":"..."}}，App 會直接顯示 message。 */
	static ResponseEntity<ErrorResponse> error(HttpStatusCode status, String code, String message) {
		return ResponseEntity.status(status).contentType(JSON_UTF8).body(ErrorResponse.of(code, message));
	}
}
