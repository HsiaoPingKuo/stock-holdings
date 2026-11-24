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
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import io.hsiaopingkuo.stockholdings.dto.ErrorResponse;
import io.hsiaopingkuo.stockholdings.dto.OversellErrorResponse;
import io.hsiaopingkuo.stockholdings.exception.DuplicateCodeException;
import io.hsiaopingkuo.stockholdings.exception.HoldingBusyException;
import io.hsiaopingkuo.stockholdings.exception.HoldingDataException;
import io.hsiaopingkuo.stockholdings.exception.HoldingNotFoundException;
import io.hsiaopingkuo.stockholdings.exception.IdempotencyKeyRequiredException;
import io.hsiaopingkuo.stockholdings.exception.IdempotencyKeyReusedException;
import io.hsiaopingkuo.stockholdings.exception.MarketAmbiguousException;
import io.hsiaopingkuo.stockholdings.exception.MarketUnavailableException;
import io.hsiaopingkuo.stockholdings.exception.RequestInProgressException;
import io.hsiaopingkuo.stockholdings.exception.StockCodeNotFoundException;
import io.hsiaopingkuo.stockholdings.exception.TradeNotFoundException;
import io.hsiaopingkuo.stockholdings.exception.TradeOversellException;
import io.hsiaopingkuo.stockholdings.exception.TradeValidationException;
import io.hsiaopingkuo.stockholdings.exception.UpgradeRequiredException;
import io.hsiaopingkuo.stockholdings.exception.VersionConflictException;
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
	ResponseEntity<ErrorResponse> typeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
		// 第 6 題 /api/v2：網址參數（DELETE 的 ?version=abc）型別不對是請求寫錯，不是「找不到」
		if (e.getParameter().hasParameterAnnotation(RequestParam.class)) {
			return ApiJson.error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", e.getName() + " 必須是整數");
		}
		// /api/v2/trades/abc：找的是交易，不是持股。/api/v1 只有持股，訊息維持原樣
		if (request.getRequestURI().startsWith(request.getContextPath() + "/api/v2/trades/")) {
			return ApiJson.error(HttpStatus.NOT_FOUND, "NOT_FOUND", "找不到這筆交易（id=" + e.getValue() + "）");
		}
		return ApiJson.error(HttpStatus.NOT_FOUND, "NOT_FOUND", "找不到這筆持股（id=" + e.getValue() + "）");
	}

	// ===== 第 6 題 /api/v2：交易的寫入（TradeService、IdempotencyService 丟出的錯誤）=====

	@ExceptionHandler(TradeNotFoundException.class)
	ResponseEntity<ErrorResponse> tradeNotFound(TradeNotFoundException e) {
		return ApiJson.error(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
	}

	/** 日期、股數、價格不符合規則（TradeRules），和 Bean Validation 的錯誤同一個代碼。 */
	@ExceptionHandler(TradeValidationException.class)
	ResponseEntity<ErrorResponse> tradeInvalid(TradeValidationException e) {
		return ApiJson.error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", e.getMessage());
	}

	/**
	 * 超賣：409，error 裡多帶兩個交易 id，App 2.0 可以標出是哪一筆（值是 null 也照樣輸出）。
	 * 輸入本身沒錯，是和目前的持股狀態衝突，所以和其他衝突一樣用 409。
	 */
	@ExceptionHandler(TradeOversellException.class)
	ResponseEntity<OversellErrorResponse> oversell(TradeOversellException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).contentType(ApiJson.JSON_UTF8)
				.body(OversellErrorResponse.of(e.getMessage(), e.getOversoldTradeId(), e.getEstimatedBuyTradeId()));
	}

	/** 別的裝置已經改過這筆交易（版本號不一致）。 */
	@ExceptionHandler(VersionConflictException.class)
	ResponseEntity<ErrorResponse> versionConflict(VersionConflictException e) {
		return ApiJson.error(HttpStatus.CONFLICT, "VERSION_CONFLICT", e.getMessage());
	}

	@ExceptionHandler(IdempotencyKeyRequiredException.class)
	ResponseEntity<ErrorResponse> idempotencyKeyInvalid(IdempotencyKeyRequiredException e) {
		return ApiJson.error(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", e.getMessage());
	}

	/** 沒帶必要的 header。目前只有 Idempotency-Key 是必要的 header。 */
	@ExceptionHandler(MissingRequestHeaderException.class)
	ResponseEntity<ErrorResponse> missingHeader(MissingRequestHeaderException e) {
		if (TradeApiV2Controller.IDEMPOTENCY_KEY.equalsIgnoreCase(e.getHeaderName())) {
			return ApiJson.error(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
					"缺少 Idempotency-Key header：每一個新增、修改、刪除都要帶一個 UUID，重送時沿用同一個");
		}
		return ApiJson.error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "缺少 " + e.getHeaderName() + " header");
	}

	/** 沒帶必要的網址參數（DELETE 沒帶 ?version=N）。沒有這個 handler 會掉到最後防線變成 500。 */
	@ExceptionHandler(MissingServletRequestParameterException.class)
	ResponseEntity<ErrorResponse> missingParameter(MissingServletRequestParameterException e) {
		return ApiJson.error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "缺少網址參數 " + e.getParameterName());
	}

	/** 同一個 Idempotency-Key 已經用在另一個 API 或不同的內容。422：格式沒錯，但這個鍵不能這樣用。 */
	@ExceptionHandler(IdempotencyKeyReusedException.class)
	ResponseEntity<ErrorResponse> idempotencyKeyReused(IdempotencyKeyReusedException e) {
		return ApiJson.error(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED", e.getMessage());
	}

	/** 同一個 Idempotency-Key 的第一個請求還沒處理完。 */
	@ExceptionHandler(RequestInProgressException.class)
	ResponseEntity<ErrorResponse> requestInProgress(RequestInProgressException e) {
		return ApiJson.error(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", e.getMessage());
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

	// ===== 第 6 題：持股改由交易計算後的三種衝突（設計決定 3、6-B）=====
	// 都用 409：使用者送的資料本身沒錯，是和持股目前的狀態衝突

	/** 有多筆交易、零股或配過股，不能用 App 1.0 的方式修改、刪除。 */
	@ExceptionHandler(UpgradeRequiredException.class)
	ResponseEntity<ErrorResponse> upgradeRequired(UpgradeRequiredException e) {
		return ApiJson.error(HttpStatus.CONFLICT, "UPGRADE_REQUIRED", e.getMessage());
	}

	/** 別的裝置正在修改同一檔持股，等不到鎖。 */
	@ExceptionHandler(HoldingBusyException.class)
	ResponseEntity<ErrorResponse> holdingBusy(HoldingBusyException e) {
		return ApiJson.error(HttpStatus.CONFLICT, "HOLDING_BUSY", e.getMessage());
	}

	/** 重播交易時超賣（資料被手動改過），股數算不出來。 */
	@ExceptionHandler(HoldingDataException.class)
	ResponseEntity<ErrorResponse> holdingDataError(HoldingDataException e) {
		return ApiJson.error(HttpStatus.CONFLICT, "DATA_ERROR", e.getMessage());
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

	// 欄位錯誤的排序：照 JSON 請求裡的欄位順序（Java 物件的屬性名稱）；前三個是 v1，後面是 v2（第 6 題）
	private static final List<String> FIELD_ORDER = List.of("code", "lots", "buyPrice",
			"side", "date", "shares", "price", "version");

	// 型別不對時，各欄位（JSON 的欄位名稱）的訊息；前三個是 v1，後面是 v2（第 6 題）
	private static final Map<String, String> FIELD_TYPE_MESSAGES = Map.of(
			"code", "股票代號必須是文字，例如 \"2330\"",
			"lots", "張數必須是 1～999 的整數",
			"buy_price", "買進價必須是數字，最多兩位小數",
			"side", "side 必須是 \"BUY\" 或 \"SELL\"（大寫）",
			"date", "日期必須是 \"YYYY-MM-DD\" 格式的文字，例如 \"2026-11-20\"",
			"shares", "股數必須是整數",
			"price", "成交價必須是數字，例如 1000 或 980.5",
			"version", "version 必須是整數");
}
