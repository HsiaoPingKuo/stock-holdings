package io.hsiaopingkuo.stockholdings.quote;

import java.math.BigDecimal;

/**
 * 上市除權息明細（TWT49UDetail）裡用得到的官方數字。
 *
 * @param cashPerShare     「(每股配發現金股利)除息」，單位元
 * @param stockPerThousand 「A. 按普通股股東持股比例每千股無償配股」，單位股
 */
public record TwseDividendDetail(String code, BigDecimal cashPerShare, BigDecimal stockPerThousand) {
}
