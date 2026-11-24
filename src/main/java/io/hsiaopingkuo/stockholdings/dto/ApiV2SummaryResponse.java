package io.hsiaopingkuo.stockholdings.dto;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.hsiaopingkuo.stockholdings.calc.DividendDataStatus;

/**
 * GET /api/v2/summary。每個欄位都是數字、不會是 null（和 v1 的 summary 一致）：
 * - total_cost、realized_pnl、cash_dividends：全部持股加總（含已賣光的）
 * - market_value、unrealized_pnl：只加有收盤價的持股；quote_missing 大於 0 時代表只是部分加總
 * - total_return = realized_pnl + unrealized_pnl + cash_dividends（畫面上的三個數字加起來剛好等於它）
 * - 資料異常的持股數字不可信，全部不計入，只算進 data_error 檔數
 * 加總的是每一檔已捨入到元的數字，App 自己把清單加起來會跟總計一樣。
 *
 * @param quoteMissing    還有股數、卻沒有收盤價的檔數
 * @param dividendPending 股利資料還在更新（dividend_status 是 PENDING）的檔數
 * @param dataError       資料異常的檔數
 */
@JsonPropertyOrder({ "total_cost", "market_value", "unrealized_pnl", "realized_pnl", "cash_dividends",
		"total_return", "quote_missing", "dividend_pending", "data_error" })
public record ApiV2SummaryResponse(
		@JsonProperty("total_cost") long totalCost,
		@JsonProperty("market_value") long marketValue,
		@JsonProperty("unrealized_pnl") long unrealizedPnl,
		@JsonProperty("realized_pnl") long realizedPnl,
		@JsonProperty("cash_dividends") long cashDividends,
		@JsonProperty("total_return") long totalReturn,
		@JsonProperty("quote_missing") int quoteMissing,
		@JsonProperty("dividend_pending") int dividendPending,
		@JsonProperty("data_error") int dataError) {

	/** 用清單每一檔的回應加總：和清單用同一份數字，兩邊不會對不起來。 */
	public static ApiV2SummaryResponse from(List<HoldingV2View> views) {
		List<ApiV2HoldingResponse> items = views.stream().map(ApiV2HoldingResponse::from).toList();
		List<ApiV2HoldingResponse> valid = items.stream().filter(h -> !h.dataError()).toList();

		long realized = sum(valid.stream().map(ApiV2HoldingResponse::realizedPnl).toList());
		long unrealized = sum(valid.stream().map(ApiV2HoldingResponse::unrealizedPnl).toList());
		long cash = sum(valid.stream().map(ApiV2HoldingResponse::cashDividends).toList());
		return new ApiV2SummaryResponse(
				sum(valid.stream().map(ApiV2HoldingResponse::totalCost).toList()),
				sum(valid.stream().map(ApiV2HoldingResponse::marketValue).toList()),
				unrealized,
				realized,
				cash,
				realized + unrealized + cash,
				// 市值是 null 只有一種情況：還有股數又沒有收盤價（賣光的市值是 0）
				(int) valid.stream().filter(h -> h.marketValue() == null).count(),
				(int) items.stream().filter(h -> DividendDataStatus.PENDING.name().equals(h.dividendStatus())).count(),
				items.size() - valid.size());
	}

	/** null（沒有收盤價）不加。 */
	private static long sum(List<Long> values) {
		return values.stream().filter(Objects::nonNull).mapToLong(Long::longValue).sum();
	}
}
