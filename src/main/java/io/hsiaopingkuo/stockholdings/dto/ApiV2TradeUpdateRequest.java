package io.hsiaopingkuo.stockholdings.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

/**
 * PUT /api/v2/trades/{id} 的請求內容。買賣方向、代號不能改（要改請刪掉重新新增）。
 *
 * @param version 使用者畫面上看到的版本號（GET 回應裡交易的 version）
 */
public record ApiV2TradeUpdateRequest(
		@NotNull(message = "請輸入成交日期") LocalDate date,
		@NotNull(message = "請輸入股數") Long shares,
		@NotNull(message = "請輸入成交價") BigDecimal price,
		@NotNull(message = "請帶上這筆交易的 version") Long version) {

	/** 冪等鍵比對用的原文（同 ApiV2TradeCreateRequest.canonical）。 */
	public String canonical() {
		return "date=" + date + "\nshares=" + shares + "\nprice=" + price.stripTrailingZeros().toPlainString()
				+ "\nversion=" + version;
	}
}
