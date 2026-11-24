package io.hsiaopingkuo.stockholdings.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.hsiaopingkuo.stockholdings.entity.Market;

/**
 * 持股 LEFT JOIN 行情快取的一列（HoldingRepository.findAllForList 用 select new 建立）。
 * 第 6 題起股數、成本改由交易重播，這一列只帶「持股本身＋收盤價」，損益由 HoldingListItem 組裝時再算。
 *
 * @param name            優先用持股存的名稱，沒有時用行情快取的名稱
 * @param closePrice      最近一個有成交日的收盤價；沒有行情或從沒成交過是 null
 * @param closeDate       closePrice 是哪一天
 * @param latestTradeDate 官方已公布的最近交易日；比 closeDate 晚代表那天沒有成交
 */
public record HoldingQuoteRow(Long id, String code, String name, Market market, BigDecimal closePrice,
		LocalDate closeDate, LocalDate latestTradeDate) {

	/** 有沒有收盤價可以顯示、可以拿來計算。 */
	public boolean hasClosePrice() {
		return isUsableClose(closePrice, closeDate);
	}

	/** 最近交易日沒有成交，顯示的是更早一天的收盤價（畫面要加提示）。 */
	public boolean showsEarlierClose() {
		return hasClosePrice() && latestTradeDate != null && closeDate.isBefore(latestTradeDate);
	}

	/**
	 * 收盤價和收盤日都要有，而且收盤價要大於 0（第 4 題：外部若回傳 0.00，不是真的收盤價，當成「未取得」）。
	 * HoldingListItem 也用同一個判斷，清單和 API 的「未取得」才會一致。
	 */
	static boolean isUsableClose(BigDecimal closePrice, LocalDate closeDate) {
		return closePrice != null && closeDate != null && closePrice.signum() > 0;
	}
}
