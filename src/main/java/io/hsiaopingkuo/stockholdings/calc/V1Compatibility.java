package io.hsiaopingkuo.stockholdings.calc;

import java.math.BigDecimal;
import java.util.List;

/**
 * App 1.0（/api/v1）的相容規則（第 6 題設計決定 2、3）。
 * 2.0 改成記每一筆交易，但 App 1.0 只認得「一檔一筆、整張」；這裡負責把交易重播的結果翻成 1.0 看得懂的樣子。
 * 純計算：不碰資料庫，網頁、/api/v1 的讀取和寫入都用同一份規則。
 */
public final class V1Compatibility {

	/** 一張 = 1000 股。 */
	public static final long SHARES_PER_LOT = 1000;

	private V1Compatibility() {
	}

	/** 張數 = 股數 ÷ 1000，捨去零股：App 1.0 的張數只能是整數。 */
	public static int lots(long shares) {
		// toIntExact：規格的張數是 int，萬一超出範圍寧可丟例外，也不要默默變成錯的數字
		return Math.toIntExact(shares / SHARES_PER_LOT);
	}

	/** 不足一張的零股股數。 */
	public static long oddShares(long shares) {
		return shares % SHARES_PER_LOT;
	}

	/**
	 * /api/v1 的名稱：有零股時加註，App 1.0 只顯示名稱和整張，使用者才知道張數不是全部、該更新 App。
	 * 例：台積電【請更新 App 2.0：含零股 500 股】
	 */
	public static String apiName(String name, long shares) {
		long odd = oddShares(shares);
		return odd == 0 ? name : name + "【請更新 App 2.0：含零股 " + odd + " 股】";
	}

	/**
	 * 能不能用 App 1.0 修改、刪除（決定 3）：只有 1 筆交易、是買進、股數是整張，而且目前股數等於那筆的股數。
	 * 「目前股數等於那筆」用來排除配過股的持股：配股後改成 3 張，分不出是「現在有 3,000 股」還是「當初買 3,000 股」。
	 *
	 * @param trades        這檔的全部交易
	 * @param currentShares 重播後（含配股）的目前股數
	 */
	public static boolean isV1Editable(List<TradeInput> trades, long currentShares) {
		if (trades.size() != 1) {
			return false;
		}
		TradeInput only = trades.get(0);
		return only.side() == TradeSide.BUY
				&& only.shares() % SHARES_PER_LOT == 0
				&& currentShares == only.shares();
	}

	/**
	 * /api/v1 的 buy_price（決定 2）：條件和 {@link #isV1Editable} 相同時回那筆交易的成交價（跟 1.0 一模一樣）；
	 * 其他情況回平均成本。
	 * 不能一律回平均成本：平均成本含手續費，升級後數字會變，App 1.0 再 PUT 回來時手續費還會被算兩次。
	 *
	 * @param averageCost 重播算出的平均成本（2 位小數，HALF_UP）
	 */
	public static BigDecimal buyPrice(List<TradeInput> trades, long currentShares, BigDecimal averageCost) {
		return isV1Editable(trades, currentShares) ? trades.get(0).price() : averageCost;
	}
}
