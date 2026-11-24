package io.hsiaopingkuo.stockholdings.controller;

import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.hsiaopingkuo.stockholdings.dto.ErrorResponse;
import io.hsiaopingkuo.stockholdings.dto.StockLookupResponse;
import io.hsiaopingkuo.stockholdings.exception.MarketAmbiguousException;
import io.hsiaopingkuo.stockholdings.exception.MarketUnavailableException;
import io.hsiaopingkuo.stockholdings.exception.StockCodeNotFoundException;
import io.hsiaopingkuo.stockholdings.quote.StockLookup;
import io.hsiaopingkuo.stockholdings.service.HoldingService;

/**
 * 新增表單輸入代號時，欄位旁即時顯示名稱與市場（畫面上的 JavaScript 呼叫）。
 * 第 3 題做的，第 6 題刪掉 1.0 網頁時從 HoldingController 原樣搬出來：2.0 的新增交易表單照樣用。
 * 只是提示：按下新增時伺服器仍會重新檢查，不能只相信畫面上的結果。
 * 回傳 JSON；錯誤格式和專案慣例一致：{"error":{"code":"...","message":"..."}}。
 */
@RestController
public class StockLookupController {

	// 和新增表單的 @Pattern 相同：英數字、1～10 個字
	private static final Pattern VALID_CODE = Pattern.compile("^[A-Za-z0-9]{1,10}$");

	private final HoldingService holdingService;

	public StockLookupController(HoldingService holdingService) {
		this.holdingService = holdingService;
	}

	@GetMapping(value = "/holdings/lookup", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Object> lookup(@RequestParam(required = false) String code) {
		// 不合格的代號直接擋掉，不浪費官方查詢
		if (code == null || !VALID_CODE.matcher(code.trim()).matches()) {
			return ResponseEntity.badRequest()
					.body(ErrorResponse.of("INVALID_CODE", "股票代號只能是英文字母或數字，最多 10 個字"));
		}
		try {
			// 前後空白先去掉（同新增表單的 StringTrimmerEditor），「 2330 」和「2330」查的是同一檔
			StockLookup lookup = holdingService.lookupStock(code.trim());
			// 回傳正規化後的代號（例如輸入 00631l 回 00631L），畫面顯示和實際儲存的一致
			return ResponseEntity.ok(StockLookupResponse.of(HoldingService.normalizeCode(code), lookup));
		} catch (StockCodeNotFoundException e) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of("STOCK_NOT_FOUND", e.getMessage()));
		} catch (MarketUnavailableException e) {
			// 503：伺服器本身正常，只是暫時拿不到官方資料
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
					.body(ErrorResponse.of("QUOTE_UNAVAILABLE", e.getMessage()));
		} catch (MarketAmbiguousException e) {
			// 和 App API 相同：503 + MARKET_UNDETERMINED。
			// 狀態碼和「連不上」一樣是 503，畫面用 error.code 分辨，才不會叫使用者「稍後再試」
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
					.body(ErrorResponse.of("MARKET_UNDETERMINED", e.getMessage()));
		}
	}
}
