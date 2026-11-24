package io.hsiaopingkuo.stockholdings.calc;

import java.time.LocalDate;

/**
 * 重播到某一筆賣出時，股數不夠（第 6 題）。
 * 放在 calc 套件，計算核心才不用依賴網頁層；要顯示給使用者的完整訊息（例如提到推估的買進）由 service 組。
 */
public class OversellException extends RuntimeException {

	private final long tradeId;
	private final LocalDate tradeDate;
	private final long heldShares;
	private final long sellShares;
	private final boolean mayMissStockDividend;
	private final boolean sameDayBuyAfter;

	/**
	 * @param heldShares           賣出前持有的股數
	 * @param mayMissStockDividend 這筆賣出之前，有可能含配股的事件「無法取得」，所以配股可能沒有算進持股
	 * @param sameDayBuyAfter      同一天、排在這筆賣出後面還有買進（同一天依輸入順序計算，買進還沒算進來）
	 */
	public OversellException(long tradeId, LocalDate tradeDate, long heldShares, long sellShares,
			boolean mayMissStockDividend, boolean sameDayBuyAfter) {
		super(buildMessage(tradeId, tradeDate, heldShares, sellShares, mayMissStockDividend, sameDayBuyAfter));
		this.tradeId = tradeId;
		this.tradeDate = tradeDate;
		this.heldShares = heldShares;
		this.sellShares = sellShares;
		this.mayMissStockDividend = mayMissStockDividend;
		this.sameDayBuyAfter = sameDayBuyAfter;
	}

	private static String buildMessage(long tradeId, LocalDate tradeDate, long heldShares, long sellShares,
			boolean mayMissStockDividend, boolean sameDayBuyAfter) {
		String message = "交易 id " + tradeId + "（" + tradeDate + "）賣出 " + sellShares + " 股，但當時只持有 " + heldShares
				+ " 股";
		if (mayMissStockDividend) {
			message += "；有除權資料無法取得，配股可能未計入";
		}
		// 只在真的是順序造成時才說明，其他原因的超賣不要被這句話誤導
		if (sameDayBuyAfter) {
			message += "；同一天的交易依輸入順序計算，請先輸入買進，或修改日期";
		}
		return message;
	}

	public long getTradeId() {
		return tradeId;
	}

	public LocalDate getTradeDate() {
		return tradeDate;
	}

	public long getHeldShares() {
		return heldShares;
	}

	public long getSellShares() {
		return sellShares;
	}

	public boolean isMayMissStockDividend() {
		return mayMissStockDividend;
	}

	public boolean isSameDayBuyAfter() {
		return sameDayBuyAfter;
	}
}
