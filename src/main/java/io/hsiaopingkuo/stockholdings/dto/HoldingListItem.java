package io.hsiaopingkuo.stockholdings.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.hsiaopingkuo.stockholdings.entity.Market;

/**
 * 清單頁的一列：持股本身加上行情快取（holding LEFT JOIN quote）。
 * 只從資料庫組出來，打開清單不會呼叫官方。
 *
 * @param name            優先用持股存的名稱，沒有時用行情快取的名稱
 * @param closePrice      最近一個有成交日的收盤價；沒有行情或從沒成交過是 null（畫面顯示「未取得」）
 * @param closeDate       closePrice 是哪一天
 * @param latestTradeDate 官方已公布的最近交易日；比 closeDate 晚代表那天沒有成交
 */
public record HoldingListItem(Long id, String code, String name, Market market, int lots, BigDecimal buyPrice,
		BigDecimal closePrice, LocalDate closeDate, LocalDate latestTradeDate) {

	/** 有沒有收盤價可以顯示。 */
	public boolean hasClosePrice() {
		return closePrice != null && closeDate != null;
	}

	/** 最近交易日沒有成交，顯示的是更早一天的收盤價（畫面要加提示）。 */
	public boolean showsEarlierClose() {
		return hasClosePrice() && latestTradeDate != null && closeDate.isBefore(latestTradeDate);
	}
}
