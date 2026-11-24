package io.hsiaopingkuo.stockholdings.calc;

/** 除權息種類（第 6 題），對應官方的「息／權／權息」。 */
public enum DividendKind {
	/** 息：只發現金股利。 */
	CASH,
	/** 權：除權（配股或現金增資）。只有現金增資時沒有配股，例如 3311 閎暉 2025-06-19。 */
	STOCK,
	/** 權息：同一天現金和配股都有。 */
	CASH_AND_STOCK
}
