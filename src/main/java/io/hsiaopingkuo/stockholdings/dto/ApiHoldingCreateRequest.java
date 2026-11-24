package io.hsiaopingkuo.stockholdings.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * App API 新增持股的請求：{ "code": "2330", "lots": 1, "buy_price": 1000 }（第 5 題）。
 * 檢查規則和網頁的 HoldingCreateForm 相同。型別不對（張數 1.5、字串數字）在讀 JSON 時就失敗（見 JacksonConfig）。
 *
 * 用 Integer、BigDecimal 而不是 int：欄位缺少或是 null 時才會是 null，交給 @NotNull 回「請輸入…」，不會被當成 0。
 */
// App 會忽略它不認得的欄位，反過來伺服器也一樣：多送的欄位不回錯誤
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiHoldingCreateRequest(
		@NotBlank(message = "請輸入股票代號")
		@Size(max = 10, message = "股票代號最多 10 個字")
		// 用 * 而不是 +：空字串只由 @NotBlank 回「請輸入股票代號」，不會同時出現兩個訊息
		@Pattern(regexp = "^[A-Za-z0-9]*$", message = "股票代號只能是英文字母或數字")
		String code,

		@NotNull(message = "請輸入張數")
		@Min(value = 1, message = "張數必須是 1～999 的整數")
		@Max(value = 999, message = "張數必須是 1～999 的整數")
		Integer lots,

		@JsonProperty("buy_price")
		@NotNull(message = "請輸入買進價")
		@DecimalMin(value = "0", inclusive = false, message = "買進價必須大於 0")
		@Digits(integer = 10, fraction = 2, message = "買進價最多兩位小數（整數部分最多 10 位）")
		BigDecimal buyPrice) {

	/** 代號先去掉前後空白再檢查，和網頁表單（StringTrimmerEditor）一致：" 2330 " 視為 2330。 */
	public ApiHoldingCreateRequest {
		code = code == null ? null : code.trim();
	}
}
