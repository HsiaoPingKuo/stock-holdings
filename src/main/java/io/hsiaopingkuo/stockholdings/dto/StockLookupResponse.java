package io.hsiaopingkuo.stockholdings.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.hsiaopingkuo.stockholdings.quote.StockLookup;

/**
 * 新增表單輸入代號時的即時提示：GET /holdings/lookup?code=2330 的回應。
 * 欄位名稱用 snake_case，和之後 App API 的慣例一致；name 查不到時輸出 null，不省略欄位。
 *
 * @param code        正規化後的代號
 * @param name        股票名稱；官方資料切不出名稱時是 null
 * @param market      TWSE 或 TPEX
 * @param marketLabel 給畫面顯示的「上市」「上櫃」
 */
public record StockLookupResponse(
		String code,
		String name,
		String market,
		@JsonProperty("market_label") String marketLabel) {

	public static StockLookupResponse of(String code, StockLookup lookup) {
		return new StockLookupResponse(code, lookup.name(), lookup.market().name(), lookup.market().getLabel());
	}
}
