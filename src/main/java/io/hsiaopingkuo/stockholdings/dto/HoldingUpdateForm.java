package io.hsiaopingkuo.stockholdings.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 網頁「修改持股」表單。
 * 刻意沒有代號欄位：代號建立後不能改（和 App API 的 PUT 規格一致），就算有人偷送 code 也不會被收下。
 * 張數、買進價的規則和新增表單相同。
 */
public class HoldingUpdateForm {

	@NotNull(message = "請輸入張數")
	@Min(value = 1, message = "張數必須是 1～999 的整數")
	@Max(value = 999, message = "張數必須是 1～999 的整數")
	private Integer lots;

	@NotNull(message = "請輸入買進價")
	@DecimalMin(value = "0", inclusive = false, message = "買進價必須大於 0")
	@Digits(integer = 10, fraction = 2, message = "買進價最多兩位小數（整數部分最多 10 位）")
	private BigDecimal buyPrice;

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
