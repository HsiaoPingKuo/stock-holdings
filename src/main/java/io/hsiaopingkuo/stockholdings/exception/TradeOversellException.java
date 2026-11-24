package io.hsiaopingkuo.stockholdings.exception;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.hsiaopingkuo.stockholdings.calc.OversellException;

/**
 * 這次新增、修改或刪除交易後，重播時某一筆賣出股數不夠（第 6 題決定 8 的超賣檢查），所以拒絕這次操作。
 * API 回 409 OVERSELL：輸入本身沒錯，是和目前的持股狀態衝突。
 *
 * 訊息直接給使用者看：說明哪一天、賣出幾股不夠賣，並帶出可能的原因
 * （同一天依輸入順序、配股可能未計入、被推估日期的買進擋住）。
 */
public class TradeOversellException extends RuntimeException {

	// 哪一筆賣出不夠賣；null 代表就是這次要新增、還沒有 id 的那一筆
	private final Long oversoldTradeId;
	// 擋住這筆賣出的「推估日期買進」；不是這個原因時是 null
	private final Long estimatedBuyTradeId;

	private TradeOversellException(String message, Long oversoldTradeId, Long estimatedBuyTradeId,
			Throwable cause) {
		super(message, cause);
		this.oversoldTradeId = oversoldTradeId;
		this.estimatedBuyTradeId = estimatedBuyTradeId;
	}

	/** 代號還沒有持股，第一筆就是賣出。 */
	public static TradeOversellException noHolding(String code) {
		return new TradeOversellException(code + " 目前沒有持股，不能賣出", null, null, null);
	}

	/**
	 * 把計算核心的 OversellException 轉成給使用者看的訊息。
	 *
	 * @param oversoldTradeId 超賣的那一筆；是這次新增的（還沒有 id）時傳 null
	 * @param estimatedBuy    排在這筆賣出後面、日期是推估的買進；沒有時傳 null
	 */
	public static TradeOversellException from(OversellException e, Long oversoldTradeId, EstimatedBuy estimatedBuy) {
		List<String> parts = new ArrayList<>();
		parts.add(e.getTradeDate() + " 的賣出 " + formatShares(e.getSellShares()) + " 股不夠賣：當時只持有 "
				+ formatShares(e.getHeldShares()) + " 股");
		if (estimatedBuy != null) {
			// 決定 1：1.0 轉入、App 1.0 新增的買進用建立日期推估，實際買進日通常更早，補登更早的賣出就會被它擋住
			parts.add(estimatedBuy.date() + " 那筆 " + formatShares(estimatedBuy.shares())
					+ " 股的買進日期是推估的，實際買進日可能更早，請先把它改成實際的買進日期");
		}
		if (e.isSameDayBuyAfter()) {
			parts.add("同一天的交易依輸入順序計算，請先輸入買進，或修改日期");
		}
		if (e.isMayMissStockDividend()) {
			parts.add("有除權資料無法取得，配股可能未計入");
		}
		return new TradeOversellException(String.join("；", parts), oversoldTradeId,
				estimatedBuy == null ? null : estimatedBuy.tradeId(), e);
	}

	// 千分位：1000 股顯示成 1,000 股，比較好讀；Locale.ROOT 讓分隔符號固定是逗號
	private static String formatShares(long shares) {
		return String.format(Locale.ROOT, "%,d", shares);
	}

	/** 一筆日期是推估的買進。 */
	public record EstimatedBuy(long tradeId, LocalDate date, long shares) {
	}

	public Long getOversoldTradeId() {
		return oversoldTradeId;
	}

	public Long getEstimatedBuyTradeId() {
		return estimatedBuyTradeId;
	}
}
