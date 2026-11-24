package io.hsiaopingkuo.stockholdings.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 409 OVERSELL 的錯誤回應：在共用格式 {"error":{"code","message"}} 的 error 裡多帶兩個欄位，
 * 只認得 code、message 的用戶端照樣看得懂；App 2.0 可以用 id 標出是哪一筆交易。
 */
public record OversellErrorResponse(Detail error) {

	/**
	 * @param oversoldTradeId     哪一筆賣出不夠賣；是這次要新增、還沒有 id 的那一筆時是 null
	 * @param estimatedBuyTradeId 擋住這筆賣出的「推估日期買進」，請使用者先把它改成實際日期；不是這個原因時是 null
	 */
	@JsonPropertyOrder({ "code", "message", "oversold_trade_id", "estimated_buy_trade_id" })
	public record Detail(
			String code,
			String message,
			@JsonProperty("oversold_trade_id") Long oversoldTradeId,
			@JsonProperty("estimated_buy_trade_id") Long estimatedBuyTradeId) {
	}

	public static OversellErrorResponse of(String message, Long oversoldTradeId, Long estimatedBuyTradeId) {
		return new OversellErrorResponse(new Detail("OVERSELL", message, oversoldTradeId, estimatedBuyTradeId));
	}
}
