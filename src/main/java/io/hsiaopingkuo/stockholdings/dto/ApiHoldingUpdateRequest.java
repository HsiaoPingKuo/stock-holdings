package io.hsiaopingkuo.stockholdings.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * App API 修改持股的請求：{ "lots": 2, "buy_price": 980.5 }（第 5 題）。
 * 刻意沒有 code 欄位：代號不能改。請求裡帶了 code 也只是「不認得的欄位」，直接忽略，不回錯誤。
 * 張數、買進價的規則和新增相同。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiHoldingUpdateRequest(
		@NotNull(message = "請輸入張數")
		@Min(value = 1, message = "張數必須是 1～999 的整數")
		@Max(value = 999, message = "張數必須是 1～999 的整數")
		Integer lots,

		@JsonProperty("buy_price")
		@NotNull(message = "請輸入買進價")
		@DecimalMin(value = "0", inclusive = false, message = "買進價必須大於 0")
		@Digits(integer = 10, fraction = 2, message = "買進價最多兩位小數（整數部分最多 10 位）")
		BigDecimal buyPrice) {
}
