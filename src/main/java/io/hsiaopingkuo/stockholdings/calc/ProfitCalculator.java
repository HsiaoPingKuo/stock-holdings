package io.hsiaopingkuo.stockholdings.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 盈虧計算規則（第 4 題）：假設現在用收盤價全部賣出，淨賺多少。
 * 網頁清單和第 5 題的 App API 都呼叫這裡，規則只有一份。不碰資料庫、不連網，所以只有 static 方法。
 *
 * 費率一律用字串建立 BigDecimal：new BigDecimal(0.001425) 會帶進 double 的浮點誤差。
 */
public final class ProfitCalculator {

	/** 手續費率 0.1425%，買進、賣出各收一次。 */
	static final BigDecimal FEE_RATE = new BigDecimal("0.001425");
	/** 手續費每筆最低 20 元。 */
	static final BigDecimal MIN_FEE = new BigDecimal("20");
	/** 證交稅（只有賣出收）：一般股票 0.3%。 */
	static final BigDecimal STOCK_TAX_RATE = new BigDecimal("0.003");
	/** 證交稅：ETF（代號 00 開頭）0.1%。 */
	static final BigDecimal ETF_TAX_RATE = new BigDecimal("0.001");

	private static final BigDecimal HUNDRED = new BigDecimal("100");
	/** 收益率沒辦法算（成本是 0）時的值；固定兩位小數，和一般收益率的格式一致。 */
	private static final BigDecimal ZERO_RATE = new BigDecimal("0.00");

	private ProfitCalculator() {
	}

	/**
	 * @param code       股票代號，用來判斷是不是 ETF
	 * @param shares     股數（張數 × 1000）
	 * @param buyPrice   買進價（每股）
	 * @param closePrice 收盤價（每股）；null 代表還沒有收盤價，只算買進那一邊
	 * @throws IllegalArgumentException 代號空白、股數或價格不大於 0
	 */
	public static ProfitResult calculate(String code, long shares, BigDecimal buyPrice, BigDecimal closePrice) {
		if (code == null || code.isBlank()) {
			throw new IllegalArgumentException("股票代號不可以是空白");
		}
		if (shares <= 0) {
			throw new IllegalArgumentException("股數必須大於 0：" + shares);
		}
		requirePositive(buyPrice, "買進價");
		if (closePrice != null) {
			requirePositive(closePrice, "收盤價");
		}

		BigDecimal shareCount = BigDecimal.valueOf(shares);
		// 金額不捨入：價格最多兩位小數（資料庫是 DECIMAL(12,2)），股數又是 1000 的倍數，乘出來一定是整數元
		BigDecimal buyAmount = buyPrice.multiply(shareCount);
		BigDecimal buyFee = fee(buyAmount);
		BigDecimal cost = buyAmount.add(buyFee);

		if (closePrice == null) {
			return new ProfitResult(buyAmount, buyFee, null, null, null, cost, null, null, null);
		}

		BigDecimal sellAmount = closePrice.multiply(shareCount);
		BigDecimal sellFee = fee(sellAmount);
		BigDecimal tax = transactionTax(code, sellAmount);
		BigDecimal profit = sellAmount.subtract(sellFee).subtract(tax).subtract(buyAmount).subtract(buyFee);
		// 市值就是用收盤價全部賣出的成交金額，和賣出金額相同
		return new ProfitResult(buyAmount, buyFee, sellAmount, sellFee, tax, cost, sellAmount, profit,
				returnRate(profit, cost));
	}

	/**
	 * 收益率 = 盈虧 ÷ 成本 × 100，四捨五入到小數第二位（負數也是 HALF_UP：-3.375 → -3.38）。
	 * 總計也用這個方法，兩邊的規則才會一致。
	 * 先乘 100 再一次除完：如果先除再乘，會多捨入一次，結果可能差 0.01。
	 *
	 * @return 成本是 0（沒有任何持股）時回 0.00，不做除法
	 */
	public static BigDecimal returnRate(BigDecimal profit, BigDecimal cost) {
		if (cost.signum() == 0) {
			return ZERO_RATE;
		}
		return profit.multiply(HUNDRED).divide(cost, 2, RoundingMode.HALF_UP);
	}

	/** 手續費：先無條件捨去到元，再套用最低 20 元（例：14.25 → 14 → 20）。 */
	static BigDecimal fee(BigDecimal amount) {
		BigDecimal fee = amount.multiply(FEE_RATE).setScale(0, RoundingMode.DOWN);
		return fee.max(MIN_FEE);
	}

	/** 證交稅：只有賣出收，ETF 和一般股票稅率不同，無條件捨去到元。 */
	static BigDecimal transactionTax(String code, BigDecimal sellAmount) {
		BigDecimal rate = isEtf(code) ? ETF_TAX_RATE : STOCK_TAX_RATE;
		return sellAmount.multiply(rate).setScale(0, RoundingMode.DOWN);
	}

	/** 題目的判斷方式：代號以 00 開頭就是 ETF（例如 0050、00631L）。 */
	static boolean isEtf(String code) {
		return code.startsWith("00");
	}

	private static void requirePositive(BigDecimal price, String label) {
		if (price == null || price.signum() <= 0) {
			throw new IllegalArgumentException(label + "必須大於 0：" + price);
		}
	}
}
