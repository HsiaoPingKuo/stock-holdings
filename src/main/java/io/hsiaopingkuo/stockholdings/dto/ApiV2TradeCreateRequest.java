package io.hsiaopingkuo.stockholdings.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.hsiaopingkuo.stockholdings.calc.TradeSide;
import jakarta.validation.constraints.NotNull;

/**
 * POST /api/v2/trades 的請求內容。這裡只檢查「有沒有填」；
 * 日期範圍、週末、股數、價格的規則在 TradeService（TradeRules），和網頁共用同一套，不在這裡再寫一份。
 * 型別不對（股數 1.5、價格 "1000"、side 小寫）由 JacksonConfig 的嚴格設定擋下，回 400。
 */
public record ApiV2TradeCreateRequest(
		@NotNull(message = "請輸入股票代號") String code,
		@NotNull(message = "請選擇 side：BUY 或 SELL") TradeSide side,
		@NotNull(message = "請輸入成交日期") LocalDate date,
		@NotNull(message = "請輸入股數") Long shares,
		@NotNull(message = "請輸入成交價") BigDecimal price) {

	/**
	 * 冪等鍵比對「內容是否相同」用的原文。價格去掉尾端的 0：1000 和 1000.00 是同一個價格，重送時不會被誤判成內容不同。
	 */
	public String canonical() {
		return "code=" + code + "\nside=" + side + "\ndate=" + date + "\nshares=" + shares + "\nprice="
				+ price.stripTrailingZeros().toPlainString();
	}
}
