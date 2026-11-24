package io.hsiaopingkuo.stockholdings.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.hsiaopingkuo.stockholdings.calc.ProfitCalculator;
import io.hsiaopingkuo.stockholdings.calc.ProfitResult;
import io.hsiaopingkuo.stockholdings.entity.Market;

/**
 * 清單頁的一列：持股本身加上行情快取（holding LEFT JOIN quote）。
 * 只從資料庫組出來，打開清單不會呼叫官方。
 *
 * @param name            優先用持股存的名稱，沒有時用行情快取的名稱
 * @param closePrice      最近一個有成交日的收盤價；沒有行情或從沒成交過是 null（畫面顯示「未取得」）
 * @param closeDate       closePrice 是哪一天
 * @param latestTradeDate 官方已公布的最近交易日；比 closeDate 晚代表那天沒有成交
 * @param profit          第 4 題：用收盤價全部賣出的盈虧；沒有收盤價時只有成本，盈虧、收益率是 null
 */
public record HoldingListItem(Long id, String code, String name, Market market, int lots, BigDecimal buyPrice,
		BigDecimal closePrice, LocalDate closeDate, LocalDate latestTradeDate, ProfitResult profit) {

	/**
	 * 給 HoldingRepository.findAllForList 的查詢（select new ...）用：資料庫只提供前 9 個欄位，
	 * 盈虧在這裡當場算好，不存進資料庫，每次讀清單都是用最新的收盤價計算。
	 * 網頁和第 5 題的 API 都拿這個物件，盈虧只會算一次、兩邊數字一定相同。
	 */
	public HoldingListItem(Long id, String code, String name, Market market, int lots, BigDecimal buyPrice,
			BigDecimal closePrice, LocalDate closeDate, LocalDate latestTradeDate) {
		this(id, code, name, market, lots, buyPrice, closePrice, closeDate, latestTradeDate,
				// 和 hasClosePrice() 同一個判斷：畫面顯示「未取得」的列，就不會算出盈虧
				ProfitCalculator.calculate(code, lots * 1000L, buyPrice,
						isUsableClose(closePrice, closeDate) ? closePrice : null));
	}

	/** 有沒有收盤價可以顯示。 */
	public boolean hasClosePrice() {
		return isUsableClose(closePrice, closeDate);
	}

	/**
	 * 收盤價和收盤日都要有，而且收盤價要大於 0。
	 * 第 4 題加上「大於 0」：外部（例如測試伺服器）若回傳 0.00，計算類別會拒絕，清單頁會整頁錯誤；
	 * 0 元不是真的收盤價，當成「未取得」處理。
	 * 寫成 static：上面的建構子在 this(...) 之前不能呼叫物件自己的方法。
	 */
	private static boolean isUsableClose(BigDecimal closePrice, LocalDate closeDate) {
		return closePrice != null && closeDate != null && closePrice.signum() > 0;
	}

	/** 最近交易日沒有成交，顯示的是更早一天的收盤價（畫面要加提示）。 */
	public boolean showsEarlierClose() {
		return hasClosePrice() && latestTradeDate != null && closeDate.isBefore(latestTradeDate);
	}
}
