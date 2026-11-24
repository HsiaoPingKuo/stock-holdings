package io.hsiaopingkuo.stockholdings.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;

import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.quote.DividendListRow;
import io.hsiaopingkuo.stockholdings.quote.TwseDividendDetail;

/**
 * 決定一次除權息要用哪個數字、標示哪種來源（第 6 題設計決定 7-C 的表，由上往下比對）。
 * 純計算：不連網、不碰資料庫，同樣的輸入一定得到同樣的結果。
 *
 * 背景：上市列表只有「權值+息值」＝前收 − 除權息參考價，是股價差額，不是配發的股利。
 * 只除息時兩者相等；有配股或現金增資時，一個式子拆不出兩個未知數，只能估算或標成無法取得。
 */
public final class DividendSourceRule {

	private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
	/** 估算配股率時，除法先算到小數 20 位，避免中途捨入影響最後的 8 位。 */
	private static final int DIVISION_SCALE = 20;
	/** 估算的每千股配股保留 8 位小數（和上櫃列表的位數一樣）。 */
	private static final int ESTIMATE_SCALE = 8;

	private DividendSourceRule() {
	}

	/**
	 * @param row    除權息列表的一列
	 * @param detail 查到的上市明細；沒有明細（查不到、還沒查、查詢失敗）時傳 null
	 */
	public static ResolvedDividend resolve(DividendListRow row, TwseDividendDetail detail) {
		if (row.market() == Market.TPEX) {
			// 上櫃列表本身就有官方的現金股利和配股
			return officialOrUnavailable(row.listedCashPerShare(), row.listedStockPerThousand());
		}
		if (detail != null) {
			return officialOrUnavailable(detail.cashPerShare(), detail.stockPerThousand());
		}
		return switch (row.kind()) {
			case CASH -> resolveCashOnly(row);
			case STOCK -> resolveStockOnly(row);
			// 一個式子拆不出現金和配股兩個未知數
			case CASH_AND_STOCK -> ResolvedDividend.unavailable();
		};
	}

	/**
	 * 上市、沒有明細、只除息：除權息參考價 = 減除股利參考價（沒有現金增資）時，
	 * 權值+息值就是每股現金股利（實測 2330 列表 4.500020 = 明細 4.50002）。
	 */
	private static ResolvedDividend resolveCashOnly(DividendListRow row) {
		BigDecimal reference = row.exRightReference();
		BigDecimal dividendReference = row.dividendReference();
		BigDecimal rightsPlusDividend = row.rightsPlusDividend();
		if (reference == null || dividendReference == null || rightsPlusDividend == null
				|| rightsPlusDividend.signum() < 0) {
			return ResolvedDividend.unavailable();
		}
		// 用 compareTo 比大小：4.500020 和 4.50002 的小數位數不同，equals 會判成不相等
		if (reference.compareTo(dividendReference) == 0) {
			return ResolvedDividend.official(rightsPlusDividend, BigDecimal.ZERO);
		}
		return ResolvedDividend.unavailable();
	}

	/** 上市、沒有明細、只除權：照減除股利參考價、除權息參考價的關係分三種。 */
	private static ResolvedDividend resolveStockOnly(DividendListRow row) {
		BigDecimal prevClose = row.prevClose();
		BigDecimal reference = row.exRightReference();
		BigDecimal dividendReference = row.dividendReference();
		if (prevClose == null || reference == null || dividendReference == null) {
			return ResolvedDividend.unavailable();
		}
		// 減除股利參考價只扣股利和配股；等於前收代表沒有配股，只有現金增資（例如 3311 閎暉 2025-06-19）
		if (dividendReference.compareTo(prevClose) == 0) {
			return ResolvedDividend.official(BigDecimal.ZERO, BigDecimal.ZERO);
		}
		if (reference.compareTo(dividendReference) == 0) {
			// 沒有現金增資：減除股利參考價 = 前收 ÷ (1 + 配股率)，用 6 位小數的權值+息值反推比較準
			BigDecimal rightsPlusDividend = row.rightsPlusDividend();
			if (rightsPlusDividend == null) {
				return ResolvedDividend.unavailable();
			}
			return estimate(prevClose, prevClose.subtract(rightsPlusDividend));
		}
		// 有現金增資：除權息參考價也含增資的影響，只能用減除股利參考價（只有 2 位小數，誤差較大）
		return estimate(prevClose, dividendReference);
	}

	/** 每千股配股 = (前收 ÷ 去掉配股後的價格 − 1) × 1000，保留 8 位、HALF_UP。 */
	private static ResolvedDividend estimate(BigDecimal prevClose, BigDecimal priceAfterStock) {
		if (priceAfterStock.signum() <= 0) {
			return ResolvedDividend.unavailable();
		}
		BigDecimal stockPerThousand = prevClose.divide(priceAfterStock, DIVISION_SCALE, RoundingMode.HALF_UP)
				.subtract(BigDecimal.ONE)
				.multiply(THOUSAND)
				.setScale(ESTIMATE_SCALE, RoundingMode.HALF_UP);
		// 算出負數代表資料互相矛盾（除權後價格反而比前收高），不猜數字
		if (stockPerThousand.signum() < 0) {
			return ResolvedDividend.unavailable();
		}
		return ResolvedDividend.estimated(BigDecimal.ZERO, stockPerThousand);
	}

	/** 官方的數字缺一個或是負數都不能用：寧可標成無法取得，也不要算錯股利。 */
	private static ResolvedDividend officialOrUnavailable(BigDecimal cashPerShare, BigDecimal stockPerThousand) {
		if (cashPerShare == null || stockPerThousand == null || cashPerShare.signum() < 0
				|| stockPerThousand.signum() < 0) {
			return ResolvedDividend.unavailable();
		}
		return ResolvedDividend.official(cashPerShare, stockPerThousand);
	}
}
