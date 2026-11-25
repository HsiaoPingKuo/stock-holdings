package io.hsiaopingkuo.stockholdings.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import io.hsiaopingkuo.stockholdings.calc.DividendDataStatus;
import io.hsiaopingkuo.stockholdings.calc.ProfitCalculator;
import io.hsiaopingkuo.stockholdings.calc.V1Compatibility;

/**
 * 網頁清單、明細頁的一檔持股（第 6 題）。
 * 數字直接用 /api/v2 的 ApiV2HoldingResponse（已捨入到元、價格兩位小數）：網頁和 App 2.0 看到的一定相同，
 * 總計也用同一份數字加總（ApiV2SummaryResponse），每一列加起來和總計對得上。
 *
 * @param view    持股＋行情、交易、重播結果
 * @param numbers 和 /api/v2 相同的數字
 */
public record HoldingPageRow(HoldingV2View view, ApiV2HoldingResponse numbers) {

	public static HoldingPageRow of(HoldingV2View view) {
		return new HoldingPageRow(view, ApiV2HoldingResponse.from(view));
	}

	public boolean dataError() {
		return view.dataError();
	}

	/** 已賣光（股數 0）：清單上排在最後、用灰字。資料異常的股數未知，不算。 */
	public boolean soldOut() {
		return !dataError() && numbers.shares() == 0;
	}

	/**
	 * 收益率（%）= 未實現損益 ÷ 總成本 × 100（Part A 第 4 題）。
	 * 用已捨入到元的數字、沿用 ProfitCalculator.returnRate，和 /api/v1 的 return_pct 同一個算法。
	 * 資料異常、已賣光（沒有成本）、沒有收盤價時算不出來，回 null（畫面顯示「—」）。
	 */
	public BigDecimal returnRate() {
		if (dataError() || soldOut() || numbers.unrealizedPnl() == null
				|| numbers.totalCost() == null || numbers.totalCost() == 0) {
			return null;
		}
		return ProfitCalculator.returnRate(BigDecimal.valueOf(numbers.unrealizedPnl()),
				BigDecimal.valueOf(numbers.totalCost()));
	}

	/**
	 * 總計的收益率：總計的未實現損益 ÷ 總成本，規則同 /api/v1/summary（總成本 0 時是 0.00）。
	 * 還有股數的持股全部沒有收盤價時，收益率還算不出來，回 null 讓網頁顯示「—」
	 * （沿用 Part A 第 4 題的決定；只有 /api/v1 的總計照規格回 0）。
	 */
	public static BigDecimal summaryReturnRate(ApiV2SummaryResponse summary, List<HoldingPageRow> rows) {
		List<HoldingPageRow> held = rows.stream().filter(r -> !r.dataError() && !r.soldOut()).toList();
		if (!held.isEmpty() && held.stream().noneMatch(r -> r.numbers().marketValue() != null)) {
			return null;
		}
		return ProfitCalculator.returnRate(BigDecimal.valueOf(summary.unrealizedPnl()),
				BigDecimal.valueOf(summary.totalCost()));
	}

	public boolean hasClosePrice() {
		return view.row().hasClosePrice();
	}

	/** 最近交易日沒有成交，顯示的是更早一天的收盤價（沿用 Part A 的提示）。 */
	public boolean showsEarlierClose() {
		return view.row().showsEarlierClose();
	}

	public LocalDate closeDate() {
		return view.row().closeDate();
	}

	public LocalDate latestTradeDate() {
		return view.row().latestTradeDate();
	}

	public String marketLabel() {
		return view.row().market() == null ? null : view.row().market().getLabel();
	}

	public boolean dividendPending() {
		return view.dividendStatus() == DividendDataStatus.PENDING;
	}

	/** 有零股時名稱旁的小字（沿用第 3 階段 HoldingListItem.oddLotNote 的文字）；沒有零股時是 null。 */
	public String oddLotNote() {
		if (dataError()) {
			return null;
		}
		long odd = V1Compatibility.oddShares(numbers.shares());
		return odd == 0 ? null : "含零股 " + odd + " 股・App 1.0 只顯示整張，請更新 App 2.0";
	}
}
