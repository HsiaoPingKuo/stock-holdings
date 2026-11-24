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

	/**
	 * @param heldShares           賣出前持有的股數
	 * @param mayMissStockDividend 這筆賣出之前，有可能含配股的事件「無法取得」，所以配股可能沒有算進持股
	 */
	public OversellException(long tradeId, LocalDate tradeDate, long heldShares, long sellShares,
			boolean mayMissStockDividend) {
		super(buildMessage(tradeId, tradeDate, heldShares, sellShares, mayMissStockDividend));
		this.tradeId = tradeId;
		this.tradeDate = tradeDate;
		this.heldShares = heldShares;
		this.sellShares = sellShares;
		this.mayMissStockDividend = mayMissStockDividend;
	}

	private static String buildMessage(long tradeId, LocalDate tradeDate, long heldShares, long sellShares,
			boolean mayMissStockDividend) {
		String message = "交易 id " + tradeId + "（" + tradeDate + "）賣出 " + sellShares + " 股，但當時只持有 " + heldShares
				+ " 股";
		if (mayMissStockDividend) {
			message += "；有除權資料無法取得，配股可能未計入";
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
}
