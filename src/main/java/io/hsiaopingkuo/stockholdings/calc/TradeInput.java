package io.hsiaopingkuo.stockholdings.calc;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 重播用的一筆交易（第 6 題）。計算核心不碰資料庫，所以不直接用 Entity，由 service 轉成這個 record。
 *
 * @param id     交易 id：同一天同方向時依 id 排序，超賣時也用它指出是哪一筆
 * @param shares 股數，可以是零股
 * @param price  每股成交價
 */
public record TradeInput(long id, LocalDate date, TradeSide side, long shares, BigDecimal price) {

	/** 在建立時就擋掉不合理的資料，重播時才不用到處檢查。 */
	public TradeInput {
		if (date == null) {
			throw new IllegalArgumentException("交易日期不可以是空白：id " + id);
		}
		if (side == null) {
			throw new IllegalArgumentException("買賣方向不可以是空白：id " + id);
		}
		if (shares <= 0) {
			throw new IllegalArgumentException("股數必須大於 0：id " + id + "，" + shares);
		}
		if (price == null || price.signum() <= 0) {
			throw new IllegalArgumentException("價格必須大於 0：id " + id + "，" + price);
		}
	}
}
