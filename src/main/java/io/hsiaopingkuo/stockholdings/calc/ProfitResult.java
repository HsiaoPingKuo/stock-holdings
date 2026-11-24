package io.hsiaopingkuo.stockholdings.calc;

import java.math.BigDecimal;

/**
 * 一筆持股「假設用收盤價全部賣出」的計算結果（第 4 題）。金額都是整數元，收益率是百分比、兩位小數。
 *
 * 沒有收盤價時，只有買進那一邊（買進金額、買進手續費、成本）有值，其餘欄位是 null：
 * 總計要把沒有收盤價的成本也算進去，所以成本一定要有；第 5 題的 JSON 也會照樣輸出 null。
 *
 * @param cost       成本 = 買進金額 + 買進手續費（收益率的分母）
 * @param marketValue 市值 = 收盤價 × 股數（和賣出金額相同）
 * @param returnRate 收益率（%），例如 9.36 代表 9.36%
 */
public record ProfitResult(BigDecimal buyAmount, BigDecimal buyFee, BigDecimal sellAmount, BigDecimal sellFee,
		BigDecimal transactionTax, BigDecimal cost, BigDecimal marketValue, BigDecimal profit, BigDecimal returnRate) {

	/**
	 * 第 6 題：從交易重播的結果建立（清單、總計、/api/v1 用）。
	 * 重播只提供總成本、市值、未實現損益（都已捨入到元），買進金額、手續費這些明細欄位是 null；
	 * 目前網頁和 SummaryCalculator 都只用到成本、市值、盈虧、收益率。
	 * 收益率用捨入到元之後的數字計算，規則和 Part A 相同（ProfitCalculator.returnRate）。
	 *
	 * @param marketValue 沒有收盤價時 null
	 * @param profit      未實現損益；沒有收盤價時 null
	 */
	public static ProfitResult fromReplay(BigDecimal cost, BigDecimal marketValue, BigDecimal profit) {
		BigDecimal returnRate = profit == null ? null : ProfitCalculator.returnRate(profit, cost);
		return new ProfitResult(null, null, null, null, null, cost, marketValue, profit, returnRate);
	}

	/** 有收盤價，賣出那一邊和盈虧才算得出來。 */
	public boolean hasClosePrice() {
		return profit != null;
	}
}
