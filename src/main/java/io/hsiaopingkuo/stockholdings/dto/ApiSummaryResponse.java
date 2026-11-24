package io.hsiaopingkuo.stockholdings.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.hsiaopingkuo.stockholdings.calc.PortfolioSummary;

/**
 * App 1.0 API 規格的「總計物件」（第 5 題）。
 * 每個欄位都是數字、不會是 null：全部持股都沒有收盤價時，網頁顯示「—」，但規格要數字，所以這裡照樣回 0。
 *
 * @param totalCost    每筆成本加總（沒有收盤價的也算），整數元
 * @param marketValue  有收盤價的市值加總，整數元
 * @param pnl          有收盤價的盈虧加總，整數元
 * @param returnPct    pnl ÷ total_cost × 100，兩位小數
 * @param quoteMissing 取不到收盤價的筆數
 */
@JsonPropertyOrder({ "total_cost", "market_value", "pnl", "return_pct", "quote_missing" })
public record ApiSummaryResponse(
		@JsonProperty("total_cost") long totalCost,
		@JsonProperty("market_value") long marketValue,
		long pnl,
		@JsonProperty("return_pct") BigDecimal returnPct,
		@JsonProperty("quote_missing") int quoteMissing) {

	/** 第 4 題算好的總計（和網頁頁首同一個物件）轉成規格的格式。 */
	public static ApiSummaryResponse from(PortfolioSummary summary) {
		return new ApiSummaryResponse(
				ApiHoldingResponse.toWholeYuan(summary.totalCost()),
				ApiHoldingResponse.toWholeYuan(summary.marketValue()),
				ApiHoldingResponse.toWholeYuan(summary.profit()),
				summary.returnRate(),
				summary.missingCloseCount());
	}
}
