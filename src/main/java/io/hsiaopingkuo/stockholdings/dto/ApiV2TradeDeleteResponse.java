package io.hsiaopingkuo.stockholdings.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * DELETE /api/v2/trades/{id} 的回應。
 *
 * @param holdingId 被刪的交易屬於哪一檔持股；持股一起被刪時，App 用它把那一列從畫面拿掉
 * @param holding   那檔持股的最新狀態；刪掉的是最後一筆交易、持股一起被刪時是 null
 */
@JsonPropertyOrder({ "deleted_trade_id", "holding_id", "holding" })
public record ApiV2TradeDeleteResponse(
		@JsonProperty("deleted_trade_id") long deletedTradeId,
		@JsonProperty("holding_id") long holdingId,
		ApiV2HoldingResponse holding) {
}
