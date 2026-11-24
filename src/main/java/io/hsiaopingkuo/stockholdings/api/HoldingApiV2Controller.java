package io.hsiaopingkuo.stockholdings.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.hsiaopingkuo.stockholdings.dto.ApiV2HoldingDetailResponse;
import io.hsiaopingkuo.stockholdings.dto.ApiV2HoldingResponse;
import io.hsiaopingkuo.stockholdings.dto.ApiV2SummaryResponse;
import io.hsiaopingkuo.stockholdings.service.HoldingV2QueryService;

/**
 * App 2.0 的讀取 API（第 6 題）。規格寫在 repo 根目錄的 API.md。
 * 只讀資料庫，不呼叫官方；錯誤一律由 ApiExceptionHandler、ApiRouteErrorHandler 轉成 {"error":{...}}。
 */
@RestController
@RequestMapping(path = "/api/v2", produces = ApiJson.JSON_UTF8_VALUE)
public class HoldingApiV2Controller {

	private final HoldingV2QueryService queryService;

	public HoldingApiV2Controller(HoldingV2QueryService queryService) {
		this.queryService = queryService;
	}

	/** 全部持股，含已賣光但還有交易的；資料異常的也列出（data_error: true）。 */
	@GetMapping("/holdings")
	public List<ApiV2HoldingResponse> list() {
		return queryService.findAll().stream().map(ApiV2HoldingResponse::from).toList();
	}

	/** 單筆＋交易＋除權息明細。資料異常照樣回 200（附 trades），App 2.0 才找得到哪一筆有問題、修得回來。 */
	@GetMapping("/holdings/{id}")
	public ApiV2HoldingDetailResponse get(@PathVariable Long id) {
		return ApiV2HoldingDetailResponse.from(queryService.find(id));
	}

	@GetMapping("/summary")
	public ApiV2SummaryResponse summary() {
		return ApiV2SummaryResponse.from(queryService.findAll());
	}
}
