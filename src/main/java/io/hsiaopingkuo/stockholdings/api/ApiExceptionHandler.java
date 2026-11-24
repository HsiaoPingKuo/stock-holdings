package io.hsiaopingkuo.stockholdings.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import io.hsiaopingkuo.stockholdings.dto.ErrorResponse;
import io.hsiaopingkuo.stockholdings.exception.HoldingNotFoundException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * App API 的錯誤一律轉成規格的格式 {"error":{"code":"...","message":"..."}}，任何回應都不露出 stack trace。
 *
 * basePackageClasses：只處理 api 套件裡 controller 丟出的錯誤，網頁（controller 套件）的錯誤處理不受影響。
 * 注意：路徑不存在（404）、方法不支援（405）發生在「還沒選到 controller」的階段，這裡接不到，
 * 由 ApiRouteErrorHandler 處理。
 */
@RestControllerAdvice(basePackageClasses = HoldingApiController.class)
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(HoldingNotFoundException.class)
	ResponseEntity<ErrorResponse> holdingNotFound(HoldingNotFoundException e) {
		return ApiJson.error(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
	}

	/**
	 * {id} 不是數字或超出範圍（例如 /api/v1/holdings/abc）：這樣的持股不可能存在，所以和「找不到」一樣回 404。
	 * 規格裡 GET、DELETE 單筆的失敗情況只有 404 NOT_FOUND。
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ErrorResponse> typeMismatch(MethodArgumentTypeMismatchException e) {
		return ApiJson.error(HttpStatus.NOT_FOUND, "NOT_FOUND", "找不到這筆持股（id=" + e.getValue() + "）");
	}

	/**
	 * 最後防線：其他沒預料到的錯誤。完整的 stack trace 只寫進伺服器 log（之後才查得出原因），
	 * 回應只給一句固定的訊息，不把例外內容送到 App。
	 */
	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> unexpected(Exception e, HttpServletRequest request) {
		log.error("App API 發生未預期的錯誤：{} {}", request.getMethod(), request.getRequestURI(), e);
		return ApiJson.error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "伺服器發生錯誤，請稍後再試");
	}
}
