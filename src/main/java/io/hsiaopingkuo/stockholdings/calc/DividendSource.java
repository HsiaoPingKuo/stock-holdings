package io.hsiaopingkuo.stockholdings.calc;

/** 除權息數字的來源（第 6 題設計決定 7-C）。 */
public enum DividendSource {
	/** 官方：上櫃列表，或上市明細。 */
	OFFICIAL,
	/** 估算：上市沒有明細時，從「權值＋息值」反推。 */
	ESTIMATED,
	/** 無法取得：拆不出現金和配股，不猜數字，也不計入股利和配股。 */
	UNAVAILABLE
}
