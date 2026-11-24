package io.hsiaopingkuo.stockholdings.calc;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一檔持股重播交易與除權息後的結果（第 6 題）。金額都已經捨入成顯示用的值。
 *
 * @param totalShares            總股數（含配股）
 * @param totalCost              剩餘總成本（元，HALF_UP）
 * @param averageCost            平均成本（小數 2 位，HALF_UP）；股數 0 時是 null
 * @param realizedPnl            已實現損益（元，HALF_UP）
 * @param marketValue            市值 = 收盤價 × 股數，元以下捨去；沒有收盤價時 null；股數 0 時是 0
 * @param unrealizedPnl          未實現損益（元，HALF_UP）；沒有收盤價時 null；股數 0 時是 0
 * @param cumulativeCashDividend 累計現金股利（元），「無法取得」的事件不計入
 * @param totalReturn            含息總報酬 = 已實現 + 未實現 + 累計現金股利（都是捨入後的值）；未實現是 null 時也是 null
 * @param dividends              每一次除權息的明細，依除權息日排序
 */
public record HoldingReplayResult(long totalShares, BigDecimal totalCost, BigDecimal averageCost,
		BigDecimal realizedPnl, BigDecimal marketValue, BigDecimal unrealizedPnl, BigDecimal cumulativeCashDividend,
		BigDecimal totalReturn, List<DividendDetail> dividends) {

	/** 複製成不可修改的清單，結果交出去之後就不會被改動。 */
	public HoldingReplayResult {
		dividends = List.copyOf(dividends);
	}
}
