package io.hsiaopingkuo.stockholdings.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.hsiaopingkuo.stockholdings.entity.Trade;

/**
 * /api/v2 的一筆交易。
 *
 * @param side          BUY 或 SELL
 * @param date          成交日 YYYY-MM-DD
 * @param price         成交價，小數 2 位
 * @param dateEstimated 日期是推估的（1.0 轉入、App 1.0 新增的買進用建立日期推估），App 可提示使用者改成實際日期
 * @param version       修改、刪除時要帶回來的版本號；不一致回 409 VERSION_CONFLICT
 */
@JsonPropertyOrder({ "id", "side", "date", "shares", "price", "date_estimated", "version" })
public record ApiV2TradeResponse(
		long id,
		String side,
		String date,
		long shares,
		BigDecimal price,
		@JsonProperty("date_estimated") boolean dateEstimated,
		long version) {

	public static ApiV2TradeResponse from(Trade trade) {
		return new ApiV2TradeResponse(trade.getId(), trade.getSide().name(), trade.getTradeDate().toString(),
				trade.getShares(), ApiV2HoldingResponse.twoDecimals(trade.getPrice()), trade.isDateEstimated(),
				trade.getVersion());
	}
}
