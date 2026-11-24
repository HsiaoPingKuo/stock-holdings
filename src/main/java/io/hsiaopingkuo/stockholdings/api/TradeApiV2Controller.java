package io.hsiaopingkuo.stockholdings.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.hsiaopingkuo.stockholdings.dto.ApiV2HoldingResponse;
import io.hsiaopingkuo.stockholdings.dto.ApiV2TradeCreateRequest;
import io.hsiaopingkuo.stockholdings.dto.ApiV2TradeDeleteResponse;
import io.hsiaopingkuo.stockholdings.dto.ApiV2TradeResponse;
import io.hsiaopingkuo.stockholdings.dto.ApiV2TradeUpdateRequest;
import io.hsiaopingkuo.stockholdings.dto.ApiV2TradeWriteResponse;
import io.hsiaopingkuo.stockholdings.dto.IdempotencyRequest;
import io.hsiaopingkuo.stockholdings.dto.StoredResponse;
import io.hsiaopingkuo.stockholdings.entity.Trade;
import io.hsiaopingkuo.stockholdings.exception.IdempotencyKeyRequiredException;
import io.hsiaopingkuo.stockholdings.service.HoldingV2QueryService;
import io.hsiaopingkuo.stockholdings.service.IdempotencyService;
import io.hsiaopingkuo.stockholdings.service.TradeService;
import jakarta.validation.Valid;
import tools.jackson.databind.json.JsonMapper;

/**
 * App 2.0 的交易寫入 API（第 6 題）。每一個寫入都要帶 Idempotency-Key（決定 6-A）：
 * 網路斷掉、App 重送時，同一個鍵只處理一次，第二次原樣回傳第一次的狀態碼和 JSON。
 *
 * 回應在 TradeService 的資料庫交易裡（寫入後、commit 前）就轉成 JSON 字串，和冪等鍵一起存：
 * 重送時回傳的字串和第一次逐字相同。組回應只讀資料庫，不呼叫官方（F-01：這時還鎖著持股）。
 *
 * Idempotency-Key 放在第一個參數：Spring 依參數順序解析，沒帶 header 會先被擋下（400 IDEMPOTENCY_KEY_REQUIRED），
 * 不會因為請求內容也有錯而先回 VALIDATION_ERROR。
 */
@RestController
@RequestMapping(path = "/api/v2", produces = ApiJson.JSON_UTF8_VALUE)
public class TradeApiV2Controller {

	static final String IDEMPOTENCY_KEY = "Idempotency-Key";

	// V6 idempotency_key 欄位的長度
	private static final int MAX_KEY_LENGTH = 64;

	private final TradeService tradeService;
	private final IdempotencyService idempotencyService;
	private final HoldingV2QueryService queryService;
	// Spring Boot 建好的 JsonMapper：和一般回應同一套設定（null 照樣輸出），存起來的 JSON 才和直接回傳的一樣
	private final JsonMapper jsonMapper;

	public TradeApiV2Controller(TradeService tradeService, IdempotencyService idempotencyService,
			HoldingV2QueryService queryService, JsonMapper jsonMapper) {
		this.tradeService = tradeService;
		this.idempotencyService = idempotencyService;
		this.queryService = queryService;
		this.jsonMapper = jsonMapper;
	}

	/** 新增一筆買進或賣出 → 201。代號還沒有持股時（只能買進）會一起建立持股。 */
	@PostMapping("/trades")
	public ResponseEntity<String> create(@RequestHeader(IDEMPOTENCY_KEY) String key,
			@Valid @RequestBody ApiV2TradeCreateRequest request) {
		String path = "/api/v2/trades";
		StoredResponse response = idempotencyService.execute(
				idempotencyRequest(key, "POST " + path, "POST " + path + "\n" + request.canonical()),
				trade -> new StoredResponse(201, json(writeResponse(trade))),
				hook -> tradeService.addTrade(request.code(), request.side(), request.date(), request.shares(),
						request.price(), hook));
		return toEntity(response);
	}

	/** 修改交易的日期、股數、價格 → 200。 */
	@PutMapping("/trades/{id}")
	public ResponseEntity<String> update(@RequestHeader(IDEMPOTENCY_KEY) String key, @PathVariable Long id,
			@Valid @RequestBody ApiV2TradeUpdateRequest request) {
		StoredResponse response = idempotencyService.execute(
				// 雜湊的原文用實際路徑（含交易 id）：同一個鍵改送另一筆交易，會被當成內容不同（422）
				idempotencyRequest(key, "PUT /api/v2/trades/{id}", "PUT /api/v2/trades/" + id + "\n"
						+ request.canonical()),
				trade -> new StoredResponse(200, json(writeResponse(trade))),
				hook -> tradeService.updateTrade(id, request.version(), request.date(), request.shares(),
						request.price(), hook));
		return toEntity(response);
	}

	/**
	 * 刪除交易 → 200（不是 204：要回傳持股的最新狀態）。
	 * DELETE 慣例上不帶請求內容，版本號放在網址參數 ?version=N。
	 */
	@DeleteMapping("/trades/{id}")
	public ResponseEntity<String> delete(@RequestHeader(IDEMPOTENCY_KEY) String key, @PathVariable Long id,
			@RequestParam long version) {
		StoredResponse response = idempotencyService.execute(
				idempotencyRequest(key, "DELETE /api/v2/trades/{id}", "DELETE /api/v2/trades/" + id
						+ "\nversion=" + version),
				trade -> new StoredResponse(200, json(new ApiV2TradeDeleteResponse(trade.getId(),
						trade.getHoldingId(), latestHolding(trade)))),
				hook -> tradeService.deleteTrade(id, version, hook));
		return toEntity(response);
	}

	private ApiV2TradeWriteResponse writeResponse(Trade trade) {
		return new ApiV2TradeWriteResponse(ApiV2TradeResponse.from(trade), latestHolding(trade));
	}

	/** 寫入後那檔持股的最新狀態；持股被刪掉（刪了最後一筆交易）時是 null。 */
	private ApiV2HoldingResponse latestHolding(Trade trade) {
		return queryService.findAfterWrite(trade.getHoldingId()).map(ApiV2HoldingResponse::from).orElse(null);
	}

	private static IdempotencyRequest idempotencyRequest(String key, String api, String content) {
		if (key.isBlank()) {
			throw new IdempotencyKeyRequiredException("Idempotency-Key 不可以是空白，請為每一個新的操作產生一個 UUID");
		}
		if (key.length() > MAX_KEY_LENGTH) {
			throw new IdempotencyKeyRequiredException("Idempotency-Key 最長 64 個字元，建議用 UUID");
		}
		return new IdempotencyRequest(key, api, IdempotencyRequest.sha256(content));
	}

	private String json(Object body) {
		return jsonMapper.writeValueAsString(body);
	}

	private static ResponseEntity<String> toEntity(StoredResponse response) {
		return ResponseEntity.status(response.status()).contentType(ApiJson.JSON_UTF8).body(response.body());
	}
}
