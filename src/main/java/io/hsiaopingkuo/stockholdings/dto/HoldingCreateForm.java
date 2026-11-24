package io.hsiaopingkuo.stockholdings.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 網頁「新增持股」表單。
 * 檢查規則對應題目第 2 題，上限也對齊資料表欄位長度，太長的輸入在這裡就擋掉，不會到資料庫才出錯。
 * 前後空白由 controller 的 StringTrimmerEditor 先去掉，所以這裡看到的已經是修剪過的值。
 */
public class HoldingCreateForm {

	@NotBlank(message = "請輸入股票代號")
	@Size(max = 10, message = "股票代號最多 10 個字")
	@Pattern(regexp = "^[A-Za-z0-9]+$", message = "股票代號只能是英文字母或數字")
	private String code;

	// 用 Integer 而不是 int：沒填時是 null，才能顯示「請輸入張數」而不是被當成 0
	@NotNull(message = "請輸入張數")
	@Min(value = 1, message = "張數必須是 1～999 的整數")
	@Max(value = 999, message = "張數必須是 1～999 的整數")
	private Integer lots;

	@NotNull(message = "請輸入買進價")
	@DecimalMin(value = "0", inclusive = false, message = "買進價必須大於 0")
	// integer = 10 對應 DECIMAL(12,2) 的整數位數；Digits 不會去掉尾端的 0，所以 10.100 算三位小數，會被拒絕
	@Digits(integer = 10, fraction = 2, message = "買進價最多兩位小數（整數部分最多 10 位）")
	private BigDecimal buyPrice;

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public Integer getLots() {
		return lots;
	}

	public void setLots(Integer lots) {
		this.lots = lots;
	}

	public BigDecimal getBuyPrice() {
		return buyPrice;
	}

	public void setBuyPrice(BigDecimal buyPrice) {
		this.buyPrice = buyPrice;
	}
}
