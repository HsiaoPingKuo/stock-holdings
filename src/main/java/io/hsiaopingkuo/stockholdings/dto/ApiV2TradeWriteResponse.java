package io.hsiaopingkuo.stockholdings.dto;

/**
 * POST、PUT /api/v2/trades 的回應：寫入後的交易，和那檔持股的最新狀態（清單欄位，不含 trades、dividends；
 * 要明細時再 GET /api/v2/holdings/{id}）。
 */
public record ApiV2TradeWriteResponse(ApiV2TradeResponse trade, ApiV2HoldingResponse holding) {
}
