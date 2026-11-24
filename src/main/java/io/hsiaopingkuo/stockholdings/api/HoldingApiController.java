package io.hsiaopingkuo.stockholdings.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.hsiaopingkuo.stockholdings.calc.SummaryCalculator;
import io.hsiaopingkuo.stockholdings.dto.ApiHoldingCreateRequest;
import io.hsiaopingkuo.stockholdings.dto.ApiHoldingResponse;
import io.hsiaopingkuo.stockholdings.dto.ApiHoldingUpdateRequest;
import io.hsiaopingkuo.stockholdings.dto.ApiSummaryResponse;
import io.hsiaopingkuo.stockholdings.dto.HoldingListItem;
import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.service.HoldingService;
import jakarta.validation.Valid;

/**
 * 給 Android App 1.0 用的 API（第 5 題），完全照附件《App 1.0 API 規格》。App 已發布，/api/v1 之後也不能改壞。
 *
 * 這裡只做「JSON ↔ service」的轉換：業務規則在 HoldingService、計算在 calc 套件，都和網頁共用。
 * 錯誤一律由 ApiExceptionHandler、ApiRouteErrorHandler 轉成 {"error":{...}} 格式。
 *
 * 不使用 session、cookie、表單：之後網頁若加上 CSRF 防護，只要把 /api/** 排除即可，不會影響 App。
 */
@RestController
// produces 帶 charset：所有成功的回應 Content-Type 都是 application/json;charset=UTF-8
@RequestMapping(path = "/api/v1", produces = ApiJson.JSON_UTF8_VALUE)
public class HoldingApiController {

	private final HoldingService holdingService;

	public HoldingApiController(HoldingService holdingService) {
		this.holdingService = holdingService;
	}

	/** 沒有持股時回 []。只讀資料庫的行情快取，不呼叫官方。 */
	@GetMapping("/holdings")
	public List<ApiHoldingResponse> list() {
		return holdingService.findAllForList().stream().map(ApiHoldingResponse::from).toList();
	}

	@GetMapping("/holdings/{id}")
	public ApiHoldingResponse get(@PathVariable Long id) {
		return ApiHoldingResponse.from(holdingService.findListItem(id));
	}

	/**
	 * 新增：和網頁新增呼叫同一個 HoldingService.create（時間預算、同時查詢上限、快取優先、沒有收盤價時補查都一樣）。
	 * 成功回 201 和新建的持股物件；存好之後用清單同一個查詢再讀一次，收盤價、盈虧和清單上的一定相同。
	 * 不寫 consumes：Content-Type 不對時錯誤發生在讀取請求內容的階段，ApiExceptionHandler 才接得到、回 400。
	 */
	@PostMapping("/holdings")
	public ResponseEntity<ApiHoldingResponse> create(@Valid @RequestBody ApiHoldingCreateRequest request) {
		Holding created = holdingService.create(request.code(), request.lots(), request.buyPrice());
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiHoldingResponse.from(holdingService.findListItem(created.getId())));
	}

	/** 修改：只能改張數和買進價（代號不能改），和網頁修改呼叫同一個 HoldingService.update。 */
	@PutMapping("/holdings/{id}")
	public ApiHoldingResponse update(@PathVariable Long id, @Valid @RequestBody ApiHoldingUpdateRequest request) {
		holdingService.update(id, request.lots(), request.buyPrice());
		return ApiHoldingResponse.from(holdingService.findListItem(id));
	}

	/** 刪除成功回 204，沒有內容。 */
	@DeleteMapping("/holdings/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		holdingService.delete(id);
		return ResponseEntity.noContent().build();
	}

	/** 總計：和網頁頁首同一個計算（第 4 題 SummaryCalculator），用清單每一列已算好的盈虧加總。 */
	@GetMapping("/summary")
	public ApiSummaryResponse summary() {
		List<HoldingListItem> holdings = holdingService.findAllForList();
		return ApiSummaryResponse.from(
				SummaryCalculator.summarize(holdings.stream().map(HoldingListItem::profit).toList()));
	}
}
