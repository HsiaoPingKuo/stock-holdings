package io.hsiaopingkuo.stockholdings.calc;

import java.math.BigDecimal;

/**
 * 全部持股的總計（第 4 題；第 5 題 /api/v1/summary 共用）。
 * 數字照 App API 規格的總計規則，不會是 null：沒有收盤價的持股只算進總成本。
 *
 * @param totalCost         每筆成本加總，沒有收盤價的也算
 * @param marketValue       有收盤價的市值加總
 * @param profit            有收盤價的盈虧加總
 * @param returnRate        盈虧 ÷ 總成本 × 100（%），兩位小數；總成本是 0 時是 0.00
 * @param holdingCount      持股筆數（網頁用來分辨「沒有持股」和「全部都沒有收盤價」）
 * @param missingCloseCount 取不到收盤價的筆數
 */
public record PortfolioSummary(BigDecimal totalCost, BigDecimal marketValue, BigDecimal profit,
		BigDecimal returnRate, int holdingCount, int missingCloseCount) {

	/** 有持股，但每一筆都還沒有收盤價：市值、盈虧、收益率雖然是 0，其實是「還算不出來」。 */
	public boolean allMissingClose() {
		return holdingCount > 0 && missingCloseCount == holdingCount;
	}
}
