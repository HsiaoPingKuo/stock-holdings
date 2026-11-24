package io.hsiaopingkuo.stockholdings.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.hsiaopingkuo.stockholdings.calc.HoldingReplayResult;

/**
 * /api/v2 的持股（清單的一列；寫入交易後回傳的 holding 也是這個）。命名沿用 v1 的 snake_case。
 * 值是 null 的欄位照樣輸出，App 用 null 判斷「未取得」。
 *
 * - 金額（total_cost、market_value、各種損益、cash_dividends）：整數元
 * - 價格（average_cost、current_price）：小數 2 位
 * - 沒有收盤價：current_price、price_date、market_value、unrealized_pnl、total_return 是 null
 * - 已賣光（shares 0）：average_cost 是 null；market_value、unrealized_pnl 是 0（不需要收盤價）
 * - 資料異常（data_error true）：重播算不出來，shares 和所有計算出來的欄位都是 null
 *
 * @param market TWSE 或 TPEX；第 2 題的舊持股還沒補到市場時是 null
 */
@JsonPropertyOrder({ "id", "code", "name", "market", "shares", "average_cost", "total_cost", "current_price",
		"price_date", "market_value", "unrealized_pnl", "realized_pnl", "cash_dividends", "total_return",
		"dividend_status", "data_error" })
public record ApiV2HoldingResponse(
		long id,
		String code,
		String name,
		String market,
		Long shares,
		@JsonProperty("average_cost") BigDecimal averageCost,
		@JsonProperty("total_cost") Long totalCost,
		@JsonProperty("current_price") BigDecimal currentPrice,
		@JsonProperty("price_date") String priceDate,
		@JsonProperty("market_value") Long marketValue,
		@JsonProperty("unrealized_pnl") Long unrealizedPnl,
		@JsonProperty("realized_pnl") Long realizedPnl,
		@JsonProperty("cash_dividends") Long cashDividends,
		@JsonProperty("total_return") Long totalReturn,
		@JsonProperty("dividend_status") String dividendStatus,
		@JsonProperty("data_error") boolean dataError) {

	public static ApiV2HoldingResponse from(HoldingV2View view) {
		HoldingQuoteRow row = view.row();
		// 和網頁、v1 的「未取得」同一個判斷：收盤價、收盤日都要有，而且收盤價大於 0
		boolean hasClose = row.hasClosePrice();
		HoldingReplayResult r = view.replay();
		boolean ok = r != null;
		return new ApiV2HoldingResponse(
				row.id(),
				row.code(),
				row.name(),
				row.market() == null ? null : row.market().name(),
				ok ? r.totalShares() : null,
				ok ? twoDecimals(r.averageCost()) : null,
				ok ? ApiHoldingResponse.toWholeYuan(r.totalCost()) : null,
				hasClose ? twoDecimals(row.closePrice()) : null,
				// 明確轉成 YYYY-MM-DD 字串，不依賴 Jackson 對日期的預設輸出格式（同 v1）
				hasClose ? DateTimeFormatter.ISO_LOCAL_DATE.format(row.closeDate()) : null,
				ok ? ApiHoldingResponse.toWholeYuan(r.marketValue()) : null,
				ok ? ApiHoldingResponse.toWholeYuan(r.unrealizedPnl()) : null,
				ok ? ApiHoldingResponse.toWholeYuan(r.realizedPnl()) : null,
				ok ? ApiHoldingResponse.toWholeYuan(r.cumulativeCashDividend()) : null,
				ok ? ApiHoldingResponse.toWholeYuan(r.totalReturn()) : null,
				view.dividendStatus() == null ? null : view.dividendStatus().name(),
				!ok);
	}

	/** 價格固定輸出兩位小數（1100 → 1100.00），App 不用自己補零；計算端已經捨入好，這裡正常不會再進位。 */
	static BigDecimal twoDecimals(BigDecimal price) {
		return price == null ? null : price.setScale(2, RoundingMode.HALF_UP);
	}
}
