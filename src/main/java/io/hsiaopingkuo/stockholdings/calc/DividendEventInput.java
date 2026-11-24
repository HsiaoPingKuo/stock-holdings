package io.hsiaopingkuo.stockholdings.calc;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 重播用的一筆除權息事件（第 6 題）。呼叫端只傳同一個代號的事件。
 *
 * @param exDate           除權息交易日
 * @param cashPerShare     每股現金股利；「無法取得」時可以是 null
 * @param stockPerThousand 每千股無償配股；「無法取得」時可以是 null
 */
public record DividendEventInput(LocalDate exDate, DividendKind kind, BigDecimal cashPerShare,
		BigDecimal stockPerThousand, DividendSource source) {

	public DividendEventInput {
		if (exDate == null || kind == null || source == null) {
			throw new IllegalArgumentException("除權息日、種類、來源都不可以是空白");
		}
		// 「無法取得」本來就沒有數字；其他來源一定要有數字，沒有的那一項由解析端填 0，不在這裡猜
		if (source != DividendSource.UNAVAILABLE) {
			requireNotNegative(cashPerShare, "每股現金股利", exDate);
			requireNotNegative(stockPerThousand, "每千股配股", exDate);
		}
	}

	private static void requireNotNegative(BigDecimal value, String label, LocalDate exDate) {
		if (value == null || value.signum() < 0) {
			throw new IllegalArgumentException(label + "必須大於或等於 0：" + exDate + "，" + value);
		}
	}
}
