package io.hsiaopingkuo.stockholdings.calc;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 持有期間某一次除權息的明細（第 6 題）。只列可參與股數大於 0 的事件。
 *
 * @param eligibleShares      可參與股數：除權息日前一個交易日收盤時的持股
 * @param cashDividend        現金股利（元）；「無法取得」時是 null
 * @param stockDividendShares 配到的股數；「無法取得」時是 null
 */
public record DividendDetail(LocalDate exDate, DividendKind kind, long eligibleShares, BigDecimal cashDividend,
		Long stockDividendShares, DividendSource source) {
}
