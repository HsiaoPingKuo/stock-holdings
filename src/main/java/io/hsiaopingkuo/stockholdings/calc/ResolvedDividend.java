package io.hsiaopingkuo.stockholdings.calc;

import java.math.BigDecimal;

/**
 * 一次除權息最後採用的數字和來源（第 6 題設計決定 7-C）。
 *
 * @param cashPerShare     每股現金股利；UNAVAILABLE 時是 null
 * @param stockPerThousand 每千股無償配股；UNAVAILABLE 時是 null
 */
public record ResolvedDividend(BigDecimal cashPerShare, BigDecimal stockPerThousand, DividendSource source) {

	static ResolvedDividend official(BigDecimal cashPerShare, BigDecimal stockPerThousand) {
		return new ResolvedDividend(cashPerShare, stockPerThousand, DividendSource.OFFICIAL);
	}

	static ResolvedDividend estimated(BigDecimal cashPerShare, BigDecimal stockPerThousand) {
		return new ResolvedDividend(cashPerShare, stockPerThousand, DividendSource.ESTIMATED);
	}

	/** 拆不出現金和配股：不猜數字，兩個都是 null。 */
	static ResolvedDividend unavailable() {
		return new ResolvedDividend(null, null, DividendSource.UNAVAILABLE);
	}
}
