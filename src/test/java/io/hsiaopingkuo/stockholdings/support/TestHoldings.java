package io.hsiaopingkuo.stockholdings.support;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import io.hsiaopingkuo.stockholdings.calc.HoldingReplayCalculator;
import io.hsiaopingkuo.stockholdings.calc.TradeInput;
import io.hsiaopingkuo.stockholdings.calc.TradeSide;
import io.hsiaopingkuo.stockholdings.dto.HoldingListItem;
import io.hsiaopingkuo.stockholdings.dto.HoldingQuoteRow;
import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.entity.Trade;
import io.hsiaopingkuo.stockholdings.repository.HoldingRepository;
import io.hsiaopingkuo.stockholdings.repository.TradeRepository;

/**
 * 測試用的資料準備（第 6 題）：持股的張數、買進價改記在交易，
 * Part A 的測試資料「一檔 N 張、買進價 P」要寫成「持股＋一筆 N × 1000 股、價格 P 的買進」。
 * 和 V4 migration 轉換 1.0 舊資料的方式相同（日期 = 建立日期、標記推估），升級前後的數字才會一樣。
 */
public final class TestHoldings {

	private TestHoldings() {
	}

	/** 存一筆 1.0 形式的持股（持股＋一筆整張買進），回傳持股。 */
	public static Holding saveWithBuy(HoldingRepository holdingRepository, TradeRepository tradeRepository,
			String code, String name, Market market, int lots, String buyPrice, LocalDateTime createdAt) {
		Holding holding = holdingRepository.saveAndFlush(new Holding(code, name, market, createdAt));
		tradeRepository.saveAndFlush(new Trade(holding.getId(), TradeSide.BUY, createdAt.toLocalDate(),
				lots * 1000L, new BigDecimal(buyPrice), true, createdAt));
		return holding;
	}

	/**
	 * 不經過資料庫，直接組出清單的一列（給 mock service 的 controller 測試用）：
	 * 一筆 N 張、價格 P 的買進重播後的結果，和 service 實際組出來的相同。
	 */
	public static HoldingListItem listItem(long id, String code, String name, Market market, int lots,
			String buyPrice, BigDecimal closePrice, LocalDate closeDate,
			LocalDate latestTradeDate) {
		HoldingQuoteRow row = new HoldingQuoteRow(id, code, name, market, closePrice, closeDate, latestTradeDate);
		// 買進日期不影響損益（沒有除權息事件），固定一個日期即可
		List<TradeInput> trades = List.of(
				new TradeInput(id, LocalDate.of(2026, 11, 20), TradeSide.BUY, lots * 1000L, new BigDecimal(buyPrice)));
		return HoldingListItem.of(row, trades, HoldingReplayCalculator.replay(code, trades, List.of(),
				row.hasClosePrice() ? closePrice : null));
	}
}
