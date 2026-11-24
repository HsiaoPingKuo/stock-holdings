package io.hsiaopingkuo.stockholdings.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import io.hsiaopingkuo.stockholdings.calc.TradeSide;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 網頁「新增交易」表單（第 6 題）。這裡只檢查「有沒有填、格式對不對」；
 * 日期範圍、週末、股數、價格的規則在 TradeService（TradeRules），和 /api/v2 共用同一套，不在這裡再寫一份。
 * 前後空白由 controller 的 StringTrimmerEditor 先去掉。
 */
public class TradeCreateForm {

	@NotBlank(message = "請輸入股票代號")
	@Pattern(regexp = "^[A-Za-z0-9]{1,10}$", message = "股票代號只能是英文字母或數字，最多 10 個字")
	private String code;

	@NotNull(message = "請選擇買進或賣出")
	private TradeSide side;

	// <input type="date"> 送來的是 yyyy-MM-dd
	@NotNull(message = "請輸入交易日期")
	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
	private LocalDate date;

	// 用 Long：沒填時是 null 才能顯示「請輸入」；填 1.5 時轉型失敗顯示錯誤，不會被截成 1
	@NotNull(message = "請輸入股數")
	private Long shares;

	@NotNull(message = "請輸入價格")
	private BigDecimal price;

	// 一次性編號：畫面產生時給一個 UUID，送出時當成 Idempotency-Key，按兩次送出只處理一次
	private String requestId;

	/** 冪等鍵比對「內容是否相同」用的原文（同 ApiV2TradeCreateRequest.canonical）。 */
	public String canonical() {
		return "code=" + code + "\nside=" + side + "\ndate=" + date + "\nshares=" + shares + "\nprice="
				+ price.stripTrailingZeros().toPlainString();
	}

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public TradeSide getSide() {
		return side;
	}

	public void setSide(TradeSide side) {
		this.side = side;
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

	public String getRequestId() {
		return requestId;
	}

	public void setRequestId(String requestId) {
		this.requestId = requestId;
	}
}
