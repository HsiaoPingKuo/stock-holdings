package io.hsiaopingkuo.stockholdings.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import io.hsiaopingkuo.stockholdings.calc.DividendDetail;

/**
 * GET /api/v2/holdings/{id}：清單的欄位（攤平在同一層）＋ trades ＋ dividends。
 * 用 @JsonUnwrapped 攤平，不重寫一份清單欄位：兩邊的欄位和算法一定相同。
 *
 * @param trades    全部交易，重播順序（日期，同一天依輸入順序）；資料異常時照樣列出，App 才找得到哪一筆有問題
 * @param dividends 持有期間每一次除權息的明細；資料異常時算不出來，是空陣列
 */
public record ApiV2HoldingDetailResponse(
		@JsonUnwrapped ApiV2HoldingResponse holding,
		List<ApiV2TradeResponse> trades,
		List<Dividend> dividends) {

	public static ApiV2HoldingDetailResponse from(HoldingV2View view) {
		List<DividendDetail> details = view.dataError() ? List.of() : view.replay().dividends();
		return new ApiV2HoldingDetailResponse(
				ApiV2HoldingResponse.from(view),
				view.trades().stream().map(ApiV2TradeResponse::from).toList(),
				details.stream().map(Dividend::from).toList());
	}

	/**
	 * 一次除權息的明細。
	 *
	 * @param kind        CASH（息）、STOCK（權）、CASH_AND_STOCK（權息）
	 * @param cash        現金股利（整數元）；source 是 UNAVAILABLE 時 null
	 * @param stockShares 配到的股數；source 是 UNAVAILABLE 時 null
	 * @param source      OFFICIAL（官方明細）、ESTIMATED（用列表估算）、UNAVAILABLE（無法取得）
	 */
	public record Dividend(
			@JsonProperty("ex_date") String exDate,
			String kind,
			@JsonProperty("eligible_shares") long eligibleShares,
			Long cash,
			@JsonProperty("stock_shares") Long stockShares,
			String source) {

		static Dividend from(DividendDetail d) {
			return new Dividend(d.exDate().toString(), d.kind().name(), d.eligibleShares(),
					ApiHoldingResponse.toWholeYuan(d.cashDividend()), d.stockDividendShares(), d.source().name());
		}
	}
}
