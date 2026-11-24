package io.hsiaopingkuo.stockholdings.quote;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.hsiaopingkuo.stockholdings.entity.Market;

/**
 * 從官方拿到的一檔行情，準備寫進 quote 表。
 * 「最近交易日」和「最近有成交的日子」分開：當天沒成交時，收盤價要沿用前一個有成交日的。
 *
 * @param code       代號
 * @param name       名稱；不知道時是 null（不會蓋掉表裡原本的名稱）
 * @param market     市場
 * @param tradeDate  官方資料的最近交易日（不論這檔當天有沒有成交），不可以是 null
 * @param closePrice 最近一個有成交日的收盤價；沒有成交紀錄時是 null
 * @param closeDate  closePrice 是哪一天；closePrice 是 null 時也是 null
 */
public record DailyQuote(String code, String name, Market market, LocalDate tradeDate, BigDecimal closePrice,
		LocalDate closeDate) {

	/** 批次資料：一列就是一天，有收盤價時收盤日就是交易日；"--" 時兩個都是 null。 */
	public static DailyQuote ofDay(String code, String name, Market market, LocalDate tradeDate,
			BigDecimal closePrice) {
		return new DailyQuote(code, name, market, tradeDate, closePrice, closePrice == null ? null : tradeDate);
	}
}
