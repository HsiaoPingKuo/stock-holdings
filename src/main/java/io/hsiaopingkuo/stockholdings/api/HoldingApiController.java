package io.hsiaopingkuo.stockholdings.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.hsiaopingkuo.stockholdings.calc.SummaryCalculator;
import io.hsiaopingkuo.stockholdings.dto.ApiHoldingResponse;
import io.hsiaopingkuo.stockholdings.dto.ApiSummaryResponse;
import io.hsiaopingkuo.stockholdings.dto.HoldingListItem;
import io.hsiaopingkuo.stockholdings.service.HoldingService;

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

	/** 總計：和網頁頁首同一個計算（第 4 題 SummaryCalculator），用清單每一列已算好的盈虧加總。 */
	@GetMapping("/summary")
	public ApiSummaryResponse summary() {
		List<HoldingListItem> holdings = holdingService.findAllForList();
		return ApiSummaryResponse.from(
				SummaryCalculator.summarize(holdings.stream().map(HoldingListItem::profit).toList()));
	}
}
