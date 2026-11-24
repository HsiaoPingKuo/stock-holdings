package io.hsiaopingkuo.stockholdings.calc;

import java.math.BigDecimal;
import java.util.List;

/**
 * 總計規則（第 4 題；第 5 題 /api/v1/summary 共用）。
 * 輸入是每一筆已經算好的 {@link ProfitResult}，這裡只負責加總，不重算單筆，兩邊的數字才一定對得上。
 */
public final class SummaryCalculator {

	private SummaryCalculator() {
	}

	/**
	 * - 總成本：每筆成本都加，沒有收盤價的也算（買的錢已經花出去了）
	 * - 市值、盈虧：只加有收盤價的
	 * - 收益率：盈虧 ÷ 總成本，規則和單筆相同（ProfitCalculator.returnRate）
	 * 沒有任何持股時全部是 0、收益率 0.00，不會除以 0。
	 */
	public static PortfolioSummary summarize(List<ProfitResult> results) {
		BigDecimal totalCost = BigDecimal.ZERO;
		BigDecimal marketValue = BigDecimal.ZERO;
		BigDecimal profit = BigDecimal.ZERO;
		int missingClose = 0;

		for (ProfitResult r : results) {
			totalCost = totalCost.add(r.cost());
			if (r.hasClosePrice()) {
				marketValue = marketValue.add(r.marketValue());
				profit = profit.add(r.profit());
			} else {
				missingClose++;
			}
		}
		return new PortfolioSummary(totalCost, marketValue, profit, ProfitCalculator.returnRate(profit, totalCost),
				results.size(), missingClose);
	}
}
