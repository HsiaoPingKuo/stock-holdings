package io.hsiaopingkuo.stockholdings.quote;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.hsiaopingkuo.stockholdings.calc.DividendKind;
import io.hsiaopingkuo.stockholdings.entity.Market;

/**
 * 除權息列表（上市 TWT49U、上櫃 exDailyQ）的一列。
 * 兩個市場共用同一個 record，之後才能存進同一張表；只有某個市場才有的欄位，另一個市場是 null。
 * 價格欄位看不懂（例如 "--"）時是 null，由 DividendSourceRule 判成「無法取得」，不猜數字。
 *
 * @param exDate                 除權息交易日
 * @param prevClose              除權息前收盤價
 * @param exRightReference       除權息參考價（含現金增資的影響）
 * @param rightsPlusDividend     權值＋息值（＝前收 − 除權息參考價，是股價差額，不一定等於配發的股利）
 * @param dividendReference      減除股利參考價（只扣現金股利和無償配股）
 * @param listedCashPerShare     列表上的每股現金股利：只有上櫃有，上市是 null
 * @param listedStockPerThousand 列表上的每千股無償配股：只有上櫃有，上市是 null
 * @param detailStockNo          上市明細 API 的代號參數（「詳細資料」欄位）：只有上市有
 * @param detailDate             上市明細 API 的日期參數：只有上市有
 */
public record DividendListRow(Market market, LocalDate exDate, String code, String name, DividendKind kind,
		BigDecimal prevClose, BigDecimal exRightReference, BigDecimal rightsPlusDividend, BigDecimal dividendReference,
		BigDecimal listedCashPerShare, BigDecimal listedStockPerThousand, String detailStockNo, LocalDate detailDate) {
}
