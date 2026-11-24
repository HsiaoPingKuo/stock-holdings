package io.hsiaopingkuo.stockholdings.service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;

import io.hsiaopingkuo.stockholdings.exception.TradeValidationException;

/**
 * 交易輸入的檢查規則（第 6 題決定 9）。
 * 「今天」由呼叫端傳入（service 用台北時區的 Clock 算好），這裡不碰系統時間，測試可以直接指定日期。
 */
public final class TradeRules {

	// 題目範圍從 2021 年開始（實際最早的交易日是 2021-01-04），和 V4 的 ck_trade_date 一致
	public static final LocalDate EARLIEST_DATE = LocalDate.of(2021, 1, 1);

	// 100 萬張。實際不可能買到這麼多；設上限是為了重播時股數加總不會超過 long 的範圍
	public static final long MAX_SHARES = 1_000_000_000L;

	// trade.price 是 DECIMAL(12, 2)：整數最多 10 位、小數 2 位。超過的話寫入資料庫才失敗會變成 500，所以先擋
	public static final BigDecimal MAX_PRICE = new BigDecimal("9999999999.99");

	private TradeRules() {
	}

	/**
	 * 日期規則。
	 *
	 * @param checkWeekend 使用者自己填的日期要檢查；推估的日期（1.0 轉入、App 1.0 新增）沒有被改動時不檢查，
	 *                     否則使用者在週末用 App 1.0 新增的持股，之後連價格都改不了
	 */
	public static void checkDate(LocalDate date, LocalDate today, boolean checkWeekend) {
		if (date == null) {
			throw new TradeValidationException("date", "請輸入交易日期");
		}
		if (date.isBefore(EARLIEST_DATE)) {
			throw new TradeValidationException("date", "交易日期不能早於 " + EARLIEST_DATE);
		}
		if (date.isAfter(today)) {
			throw new TradeValidationException("date", "交易日期不能是未來的日期（今天是 " + today + "）");
		}
		if (checkWeekend && isWeekend(date)) {
			throw new TradeValidationException("date", date + " 是週末，不是交易日，請確認日期");
		}
	}

	public static void checkShares(long shares) {
		if (shares <= 0) {
			throw new TradeValidationException("shares", "股數必須是大於 0 的整數");
		}
		if (shares > MAX_SHARES) {
			throw new TradeValidationException("shares", "股數不能超過 1,000,000,000 股");
		}
	}

	public static void checkPrice(BigDecimal price) {
		if (price == null || price.signum() <= 0) {
			throw new TradeValidationException("price", "價格必須大於 0");
		}
		// stripTrailingZeros：10.500 其實只有兩位小數，不該被擋
		if (price.stripTrailingZeros().scale() > 2) {
			throw new TradeValidationException("price", "價格最多兩位小數");
		}
		if (price.compareTo(MAX_PRICE) > 0) {
			throw new TradeValidationException("price", "價格不能超過 9,999,999,999.99");
		}
	}

	// 國定假日不擋（決定 9）：要維護每年的休市日，而且擋錯了使用者就無法輸入
	private static boolean isWeekend(LocalDate date) {
		DayOfWeek day = date.getDayOfWeek();
		return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
	}
}
