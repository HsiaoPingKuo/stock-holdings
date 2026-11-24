package io.hsiaopingkuo.stockholdings.api;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import io.hsiaopingkuo.stockholdings.dto.ErrorResponse;
import io.hsiaopingkuo.stockholdings.exception.DuplicateCodeException;
import io.hsiaopingkuo.stockholdings.exception.HoldingNotFoundException;
import io.hsiaopingkuo.stockholdings.exception.MarketAmbiguousException;
import io.hsiaopingkuo.stockholdings.exception.MarketUnavailableException;
import io.hsiaopingkuo.stockholdings.exception.StockCodeNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.InputCoercionException;
import tools.jackson.core.exc.StreamReadException;

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

	// ===== 新增時的業務錯誤（和網頁用同一組例外，訊息也相同） =====

	@ExceptionHandler(StockCodeNotFoundException.class)
	ResponseEntity<ErrorResponse> stockNotFound(StockCodeNotFoundException e) {
		return ApiJson.error(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND", e.getMessage());
	}

	@ExceptionHandler(DuplicateCodeException.class)
	ResponseEntity<ErrorResponse> duplicateCode(DuplicateCodeException e) {
		return ApiJson.error(HttpStatus.CONFLICT, "DUPLICATE_CODE", e.getMessage());
	}

	// 規格沒有定義的兩種情況（使用者決定）：503 表示伺服器本身正常，只是現在無法確認代號
	@ExceptionHandler(MarketUnavailableException.class)
	ResponseEntity<ErrorResponse> quoteUnavailable(MarketUnavailableException e) {
		return ApiJson.error(HttpStatus.SERVICE_UNAVAILABLE, "QUOTE_UNAVAILABLE", e.getMessage());
	}

	@ExceptionHandler(MarketAmbiguousException.class)
	ResponseEntity<ErrorResponse> marketUndetermined(MarketAmbiguousException e) {
		return ApiJson.error(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_UNDETERMINED", e.getMessage());
	}

	// ===== 請求內容不對：一律 400 VALIDATION_ERROR =====

	/**
	 * 欄位檢查沒過（缺欄位、null、張數超出範圍、超過兩位小數…）。
	 * 可能同時有好幾個錯誤，依欄位順序（代號、張數、買進價）串成一句，App 直接顯示 message；
	 * Bean Validation 回報錯誤的順序不固定，所以自己排序，同樣的輸入每次都得到同樣的訊息。
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ErrorResponse> invalidFields(MethodArgumentNotValidException e) {
		String message = e.getBindingResult().getFieldErrors().stream()
				.sorted(Comparator.comparingInt((FieldError error) -> FIELD_ORDER.indexOf(error.getField()))
						.thenComparing(FieldError::getDefaultMessage, Comparator.nullsLast(Comparator.naturalOrder())))
				.map(FieldError::getDefaultMessage)
				.distinct()
				.collect(Collectors.joining("；"));
		return ApiJson.error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
	}

	/** JSON 格式壞掉、沒有請求內容，或型別不對（張數 1.5、"1"，買進價 "1000"，代號 2330 這類數字）。 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
		return ApiJson.error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", unreadableMessage(e));
	}

	/** Content-Type 不是 application/json（或沒帶）。標準是 415，規格只列了 400，照使用者決定回 400。 */
	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<ErrorResponse> unsupportedMediaType(HttpMediaTypeNotSupportedException e) {
		return ApiJson.error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "請用 Content-Type: application/json 送出 JSON");
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

	/**
	 * 讀不懂請求內容時，盡量告訴使用者是哪個欄位錯了：
	 * - JSON 語法壞掉（少括號、多逗號）→ 一句通用訊息
	 * - 型別不對 → Jackson 的例外會帶「是哪個欄位」（path），換成那個欄位的中文訊息
	 * Jackson 原本的英文訊息含有類別名稱等內部資訊，不直接回給 App。
	 */
	private static String unreadableMessage(HttpMessageNotReadableException e) {
		for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
			// 數字太大（張數 99999999999）雖然也是 StreamReadException，但屬於「型別不對」，要往下看是哪個欄位
			if (cause instanceof StreamReadException && !(cause instanceof InputCoercionException)) {
				return INVALID_JSON;
			}
			if (cause instanceof JacksonException jackson) {
				for (JacksonException.Reference ref : jackson.getPath()) {
					String fieldMessage = FIELD_TYPE_MESSAGES.get(ref.getPropertyName());
					if (fieldMessage != null) {
						return fieldMessage;
					}
				}
			}
		}
		return INVALID_JSON;
	}

	private static final String INVALID_JSON = "請求內容不是正確的 JSON";

	// 欄位錯誤的排序：照 JSON 請求裡的欄位順序（Java 物件的屬性名稱）
	private static final List<String> FIELD_ORDER = List.of("code", "lots", "buyPrice");

	// 型別不對時，各欄位（JSON 的欄位名稱）的訊息
	private static final Map<String, String> FIELD_TYPE_MESSAGES = Map.of(
			"code", "股票代號必須是文字，例如 \"2330\"",
			"lots", "張數必須是 1～999 的整數",
			"buy_price", "買進價必須是數字，最多兩位小數");
}
