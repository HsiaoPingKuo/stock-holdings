package io.hsiaopingkuo.stockholdings.dto;

import java.util.List;

import io.hsiaopingkuo.stockholdings.calc.DividendDataStatus;
import io.hsiaopingkuo.stockholdings.calc.HoldingReplayResult;
import io.hsiaopingkuo.stockholdings.entity.Trade;

/**
 * /api/v2 的一檔持股（第 6 題）：持股＋行情、全部交易、重播結果、股利資料狀態。
 * 和 v1 的 HoldingListItem 分開：v2 要已實現損益、股利這些 v1 沒有的欄位，也要列出已賣光的持股。
 *
 * @param trades         這檔的全部交易（重播順序）
 * @param replay         重播結果；重播時超賣（資料被手動改過）是 null，代表「資料異常」
 * @param dividendStatus 股利資料狀態
 */
public record HoldingV2View(HoldingQuoteRow row, List<Trade> trades, HoldingReplayResult replay,
		DividendDataStatus dividendStatus) {

	public HoldingV2View {
		trades = List.copyOf(trades);
	}

	public boolean dataError() {
		return replay == null;
	}
}
