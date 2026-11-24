package io.hsiaopingkuo.stockholdings.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.hsiaopingkuo.stockholdings.calc.ProfitResult;
import io.hsiaopingkuo.stockholdings.calc.V1Compatibility;

/**
 * App 1.0 API 規格的「持股物件」（第 5 題）。欄位名稱、型別完全照規格，App 已發布不會再改。
 * 值是 null 的欄位照樣輸出（"current_price": null），少了欄位 App 會顯示「資料格式錯誤」。
 *
 * 第 6 題起數字由交易重播算出，翻成 1.0 的格式（決定 2）：張數捨去到整張、有零股時名稱加註、金額用全部股數。
 *
 * @param name         規格是字串、不可以是 null：持股和行情快取都沒有名稱時輸出空字串；有零股時加註
 * @param market       TWSE 或 TPEX；第 2 題的舊持股、行情快取也還沒有時輸出空字串（見 README）
 * @param lots         股數 ÷ 1000 捨去
 * @param buyPrice     BigDecimal 會輸出成數字（1000.00），不是字串；單筆整張買進是成交價，其他是平均成本
 * @param currentPrice 最近一個有成交日的收盤價；取不到時 null
 * @param priceDate    currentPrice 那一天（YYYY-MM-DD）；取不到時 null
 * @param pnl          未實現損益，整數元；取不到收盤價時 null
 * @param returnPct    收益率（%），兩位小數；取不到收盤價時 null
 */
// 輸出順序照規格的範例，方便人工對照（App 不在意順序）
@JsonPropertyOrder({ "id", "code", "name", "market", "lots", "buy_price", "current_price", "price_date", "pnl",
		"return_pct" })
public record ApiHoldingResponse(
		long id,
		String code,
		String name,
		String market,
		int lots,
		@JsonProperty("buy_price") BigDecimal buyPrice,
		@JsonProperty("current_price") BigDecimal currentPrice,
		@JsonProperty("price_date") String priceDate,
		Long pnl,
		@JsonProperty("return_pct") BigDecimal returnPct) {

	/** 清單的一列（網頁也用同一個物件），轉成規格的格式；盈虧已經在 HoldingListItem 算好，這裡不重算。 */
	public static ApiHoldingResponse from(HoldingListItem item) {
		ProfitResult profit = item.profit();
		// 和網頁顯示「未取得」用同一個判斷：收盤價、收盤日都要有，而且收盤價大於 0
		boolean hasClose = item.hasClosePrice();
		return new ApiHoldingResponse(
				item.id(),
				item.code(),
				V1Compatibility.apiName(item.name() == null ? "" : item.name(), item.totalShares()),
				item.market() == null ? "" : item.market().name(),
				item.lots(),
				item.buyPrice(),
				hasClose ? item.closePrice() : null,
				// 明確轉成 YYYY-MM-DD 字串，不依賴 Jackson 對日期的預設輸出格式
				hasClose ? DateTimeFormatter.ISO_LOCAL_DATE.format(item.closeDate()) : null,
				toWholeYuan(profit.profit()),
				profit.returnRate());
	}

	/**
	 * 金額轉成整數元。計算結果是 93708.00 這種帶小數位的 BigDecimal，直接輸出 JSON 會是 93708.00，規格要整數。
	 * 第 6 題：重播已經把金額捨入到元，正常不會有小數；但零股時成交金額本身就會有小數（7 × 10.55 = 73.85），
	 * 原本的 longValueExact 遇到小數會丟例外變成 500，所以改成先用 HALF_UP 捨入到元再轉（和重播的顯示規則一致）。
	 */
	static Long toWholeYuan(BigDecimal amount) {
		return amount == null ? null : amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
	}
}
