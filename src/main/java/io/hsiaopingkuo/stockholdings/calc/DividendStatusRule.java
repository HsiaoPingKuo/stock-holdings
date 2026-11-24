package io.hsiaopingkuo.stockholdings.calc;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;

/**
 * 判斷一檔持股的股利資料狀態（第 6 題設計決定 7-D）。純計算：不碰資料庫，資料由 DividendStatusService 準備。
 */
public final class DividendStatusRule {

	/** 題目的交易從 2021 年開始，已抓範圍也從 2021 年記起。 */
	private static final int FIRST_YEAR = 2021;

	private DividendStatusRule() {
	}

	/**
	 * @param firstTradeDate         這檔的第一筆交易日；沒有交易時是 null
	 * @param today                  今天（台北時間）
	 * @param fetchRangesPerMarket   要看的每個市場各一份：年份 → 那一年抓完了沒；沒有這一年 = 還沒抓過
	 * @param hasPendingDetailInHoldingPeriod 這檔有沒有「除權息日晚於第一筆交易日、明細還在待查」的事件
	 */
	public static DividendDataStatus status(LocalDate firstTradeDate, LocalDate today,
			Collection<Map<Integer, Boolean>> fetchRangesPerMarket, boolean hasPendingDetailInHoldingPeriod) {
		// 沒有交易就沒有持有期間，不需要任何除權息資料
		if (firstTradeDate == null) {
			return DividendDataStatus.COMPLETE;
		}
		int currentYear = today.getYear();
		for (Map<Integer, Boolean> fetchRanges : fetchRangesPerMarket) {
			for (int year = Math.max(FIRST_YEAR, firstTradeDate.getYear()); year <= currentYear; year++) {
				Boolean completed = fetchRanges.get(year);
				if (completed == null) {
					return DividendDataStatus.PENDING;
				}
				// 今年永遠不會抓完，只要抓過一次就算數（股利資料每天更新一次）
				if (year < currentYear && !completed) {
					return DividendDataStatus.PENDING;
				}
			}
		}
		return hasPendingDetailInHoldingPeriod ? DividendDataStatus.PENDING : DividendDataStatus.COMPLETE;
	}
}
