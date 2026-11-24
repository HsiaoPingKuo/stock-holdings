package io.hsiaopingkuo.stockholdings.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import io.hsiaopingkuo.stockholdings.calc.HoldingReplayResult;
import io.hsiaopingkuo.stockholdings.calc.PortfolioSummary;
import io.hsiaopingkuo.stockholdings.calc.ProfitResult;
import io.hsiaopingkuo.stockholdings.calc.SummaryCalculator;
import io.hsiaopingkuo.stockholdings.calc.TradeInput;
import io.hsiaopingkuo.stockholdings.calc.V1Compatibility;
import io.hsiaopingkuo.stockholdings.entity.Market;

/**
 * 清單頁的一列（網頁和 /api/v1 共用）：持股、行情快取，加上交易重播算出的股數與損益（第 6 題）。
 * 只從資料庫組出來，打開清單不會呼叫官方；損益每次讀取時重算，不存進資料庫。
 *
 * @param totalShares 重播後的總股數（含配股）
 * @param lots        App 1.0 的張數：總股數 ÷ 1000 捨去
 * @param buyPrice    App 1.0 的買進價：單筆整張買進時是成交價，其他情況是平均成本（V1Compatibility.buyPrice）
 * @param profit      用收盤價全部賣出的未實現損益（Part A 第 4 題的定義）；沒有收盤價時只有成本
 * @param v1Editable  能不能用 App 1.0（以及目前的網頁修改畫面）修改、刪除
 * @param dataError   重播交易失敗（例如資料被手動改過而超賣）：股數、損益都不可信，數字欄位是 0 或 null
 */
public record HoldingListItem(Long id, String code, String name, Market market, long totalShares, int lots,
		BigDecimal buyPrice, BigDecimal closePrice, LocalDate closeDate, LocalDate latestTradeDate,
		ProfitResult profit, boolean v1Editable, boolean dataError) {

	/** 一檔正常的持股：重播結果翻成清單要顯示的欄位。 */
	public static HoldingListItem of(HoldingQuoteRow row, List<TradeInput> trades, HoldingReplayResult replay) {
		long shares = replay.totalShares();
		return new HoldingListItem(row.id(), row.code(), row.name(), row.market(), shares,
				V1Compatibility.lots(shares),
				V1Compatibility.buyPrice(trades, shares, replay.averageCost()),
				row.closePrice(), row.closeDate(), row.latestTradeDate(),
				ProfitResult.fromReplay(replay.totalCost(), replay.marketValue(), replay.unrealizedPnl()),
				V1Compatibility.isV1Editable(trades, shares), false);
	}

	/** 重播失敗的持股：只保留代號、名稱這些持股本身的資料，網頁顯示「資料異常」。 */
	public static HoldingListItem dataError(HoldingQuoteRow row) {
		return new HoldingListItem(row.id(), row.code(), row.name(), row.market(), 0, 0, null,
				row.closePrice(), row.closeDate(), row.latestTradeDate(), null, false, true);
	}

	/**
	 * 總計（網頁頁首、/api/v1/summary 共用）：資料異常的持股數字不可信，不計入。
	 * 加總的是每一檔已經捨入到元的數字，App 1.0 自己把清單加起來才會跟總計一樣。
	 */
	public static PortfolioSummary summarize(List<HoldingListItem> items) {
		return SummaryCalculator.summarize(items.stream()
				.filter(item -> !item.dataError())
				.map(HoldingListItem::profit)
				.toList());
	}

	/** 有沒有收盤價可以顯示（和 HoldingQuoteRow 同一個判斷）。 */
	public boolean hasClosePrice() {
		return HoldingQuoteRow.isUsableClose(closePrice, closeDate);
	}

	/** 最近交易日沒有成交，顯示的是更早一天的收盤價（畫面要加提示）。 */
	public boolean showsEarlierClose() {
		return hasClosePrice() && latestTradeDate != null && closeDate.isBefore(latestTradeDate);
	}

	/** 不足一張的零股股數。 */
	public long oddShares() {
		return V1Compatibility.oddShares(totalShares);
	}

	/**
	 * 網頁名稱旁的小字提示：張數欄只顯示整張，提醒還在用 App 1.0 的使用者更新。沒有零股時是 null（不顯示）。
	 * /api/v1 的加註在 V1Compatibility.apiName，兩邊的文字刻意不同（使用者決定）。
	 */
	public String oddLotNote() {
		long odd = oddShares();
		return odd == 0 ? null : "含零股 " + odd + " 股・App 1.0 只顯示整張，請更新 App 2.0";
	}
}
