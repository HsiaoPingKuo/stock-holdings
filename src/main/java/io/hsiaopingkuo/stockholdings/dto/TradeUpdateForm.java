package io.hsiaopingkuo.stockholdings.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import io.hsiaopingkuo.stockholdings.entity.Trade;
import jakarta.validation.constraints.NotNull;

/**
 * 網頁「修改交易」表單（第 6 題）：只能改日期、股數、價格（買賣方向不能改，同 /api/v2）。
 * version 是畫面打開時看到的版本號（隱藏欄位），別的裝置先改過就會被擋下（VERSION_CONFLICT）。
 */
public class TradeUpdateForm {

	@NotNull(message = "請輸入交易日期")
	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
	private LocalDate date;

	@NotNull(message = "請輸入股數")
	private Long shares;

	@NotNull(message = "請輸入價格")
	private BigDecimal price;

	@NotNull(message = "表單已過期，請重新整理後再送出")
	private Long version;

	private String requestId;

	/** 用目前的交易內容帶入表單（按「修改」時）。 */
	public static TradeUpdateForm of(Trade trade, String requestId) {
		TradeUpdateForm form = new TradeUpdateForm();
		form.date = trade.getTradeDate();
		form.shares = trade.getShares();
		form.price = trade.getPrice();
		form.version = trade.getVersion();
		form.requestId = requestId;
		return form;
	}

	/** 冪等鍵比對用的原文（同 ApiV2TradeUpdateRequest.canonical）。 */
	public String canonical() {
		return "date=" + date + "\nshares=" + shares + "\nprice=" + price.stripTrailingZeros().toPlainString()
				+ "\nversion=" + version;
	}

	public LocalDate getDate() {
		return date;
	}

	public void setDate(LocalDate date) {
		this.date = date;
	}

	public Long getShares() {
		return shares;
	}

	public void setShares(Long shares) {
		this.shares = shares;
	}

	public BigDecimal getPrice() {
		return price;
	}

	public void setPrice(BigDecimal price) {
		this.price = price;
	}

	public Long getVersion() {
		return version;
	}

	public void setVersion(Long version) {
		this.version = version;
	}

	public String getRequestId() {
		return requestId;
	}

	public void setRequestId(String requestId) {
		this.requestId = requestId;
	}
}
