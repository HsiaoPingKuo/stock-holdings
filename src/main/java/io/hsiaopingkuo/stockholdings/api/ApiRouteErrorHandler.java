package io.hsiaopingkuo.stockholdings.api;

import java.util.Set;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import io.hsiaopingkuo.stockholdings.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;

/**
 * App API 的「路徑不存在（404）」「方法不支援（405）」也要是 {"error":{...}} 格式。
 *
 * 為什麼不放在 ApiExceptionHandler：這兩種錯誤發生在 Spring「選 controller」的階段，
 * 這時還不知道是哪個 controller，限定 api 套件的 ControllerAdvice 不會被套用。
 * 所以這個類別不限套件，改用網址判斷：只處理 /api/ 開頭的請求；
 * 其他路徑把原本的例外再丟出去，交回 Spring 預設處理，網頁的 404、405 行為和原本完全一樣。
 */
@RestControllerAdvice
public class ApiRouteErrorHandler {

	/** 路徑不存在。沒有對應的 controller 時，Spring 會交給靜態檔案處理，找不到檔案就丟 NoResourceFoundException。 */
	@ExceptionHandler({ NoResourceFoundException.class, NoHandlerFoundException.class })
	ResponseEntity<ErrorResponse> notFound(Exception e, HttpServletRequest request) throws Exception {
		if (!isApi(request)) {
			throw e;
		}
		return ApiJson.error(HttpStatus.NOT_FOUND, "NOT_FOUND",
				"找不到這個 API：" + request.getMethod() + " " + request.getRequestURI());
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<ErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException e,
			HttpServletRequest request) throws HttpRequestMethodNotSupportedException {
		if (!isApi(request)) {
			throw e;
		}
		ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
				.contentType(ApiJson.JSON_UTF8);
		// HTTP 規定 405 要附上 Allow 標頭，告訴客戶端這個路徑支援哪些方法
		Set<HttpMethod> supported = e.getSupportedHttpMethods();
		if (supported != null && !supported.isEmpty()) {
			builder.allow(supported.toArray(HttpMethod[]::new));
		}
		return builder.body(ErrorResponse.of("METHOD_NOT_ALLOWED",
				"不支援 " + request.getMethod() + " " + request.getRequestURI()));
	}

	private static boolean isApi(HttpServletRequest request) {
		return request.getRequestURI().startsWith(request.getContextPath() + "/api/");
	}
}
