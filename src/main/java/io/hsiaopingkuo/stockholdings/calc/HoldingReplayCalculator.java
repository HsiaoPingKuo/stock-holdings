package io.hsiaopingkuo.stockholdings.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 依日期重播一檔持股的交易與除權息事件，算出股數、成本、損益與股利（第 6 題設計決定 8）。
 * 股利不存進資料庫，每次都從頭重播：補登、修改、刪除交易後結果自然會跟著變。
 * 純計算：不碰資料庫、不連網、不用 Spring。手續費、證交稅直接用 ProfitCalculator，規則只有一份。
 */
public final class HoldingReplayCalculator {

	/** 計算過程的成本、已實現損益保留的小數位數；顯示時才捨入。 */
	static final int SCALE = 10;

	private static final BigDecimal THOUSAND = new BigDecimal("1000");

	/**
	 * 同一天依輸入順序（id），不分買賣：「先賣光、再買回」時，新買的成本才不會混進前一輪（設計決定 8 A）。
	 */
	private static final Comparator<TradeInput> TRADE_ORDER = Comparator.comparing(TradeInput::date)
			.thenComparingLong(TradeInput::id);

	private HoldingReplayCalculator() {
	}

	/**
	 * @param code       股票代號，用來判斷證交稅率（ETF 0.1%）
	 * @param trades     這檔的所有交易，順序不限
	 * @param events     這檔的所有除權息事件，順序不限；同一天多筆時，明細依傳入的順序
	 * @param closePrice 收盤價；null 代表還沒有收盤價
	 * @throws OversellException        重播到某一筆賣出時股數不夠（即使最後的總股數是正的）
	 * @throws IllegalArgumentException 代號空白、收盤價不大於 0
	 */
	public static HoldingReplayResult replay(String code, List<TradeInput> trades, List<DividendEventInput> events,
			BigDecimal closePrice) {
		if (code == null || code.isBlank()) {
			throw new IllegalArgumentException("股票代號不可以是空白");
		}
		if (closePrice != null && closePrice.signum() <= 0) {
			throw new IllegalArgumentException("收盤價必須大於 0：" + closePrice);
		}

		// TreeMap 依日期排序；同一天的事件保留傳入順序，交易另外依 TRADE_ORDER（id）排
		Map<LocalDate, List<DividendEventInput>> eventsByDate = new TreeMap<>();
		for (DividendEventInput e : events) {
			eventsByDate.computeIfAbsent(e.exDate(), d -> new ArrayList<>()).add(e);
		}
		Map<LocalDate, List<TradeInput>> tradesByDate = new TreeMap<>();
		for (TradeInput t : trades.stream().sorted(TRADE_ORDER).toList()) {
			tradesByDate.computeIfAbsent(t.date(), d -> new ArrayList<>()).add(t);
		}
		TreeSet<LocalDate> dates = new TreeSet<>(eventsByDate.keySet());
		dates.addAll(tradesByDate.keySet());

		Ledger ledger = new Ledger(code);
		for (LocalDate date : dates) {
			// 同一天先處理除權息：這時的股數只含「早於除權息日」的交易，當天買的領不到、當天賣的領得到
			ledger.applyDividendDay(eventsByDate.getOrDefault(date, List.of()));
			List<TradeInput> dayTrades = tradesByDate.getOrDefault(date, List.of());
			for (int i = 0; i < dayTrades.size(); i++) {
				TradeInput t = dayTrades.get(i);
				if (t.side() == TradeSide.BUY) {
					ledger.applyBuy(t);
				} else {
					// 同一天排在後面還有買進：超賣時要提醒使用者是順序的問題，不是股數真的不夠
					boolean sameDayBuyAfter = dayTrades.subList(i + 1, dayTrades.size()).stream()
							.anyMatch(later -> later.side() == TradeSide.BUY);
					ledger.applySell(t, sameDayBuyAfter);
				}
			}
		}
		return ledger.toResult(closePrice);
	}

	/** 成交金額：價格 × 股數，元以下捨去（一筆交易視為一筆委託）。零股時才會有小數，例如 7 × 10.55 = 73.85 → 73。 */
	static BigDecimal tradeAmount(BigDecimal price, long shares) {
		return price.multiply(BigDecimal.valueOf(shares)).setScale(0, RoundingMode.DOWN);
	}

	/** 現金股利：可參與股數 × 每股現金股利，元以下捨去。 */
	static BigDecimal cashDividend(long eligibleShares, BigDecimal cashPerShare) {
		return BigDecimal.valueOf(eligibleShares).multiply(cashPerShare).setScale(0, RoundingMode.DOWN);
	}

	/** 配股：可參與股數 × 每千股配股 ÷ 1000，不足一股捨去。先乘再除，只捨入一次。 */
	static long stockDividend(long eligibleShares, BigDecimal stockPerThousand) {
		return BigDecimal.valueOf(eligibleShares)
				.multiply(stockPerThousand)
				.divide(THOUSAND, 0, RoundingMode.DOWN)
				.longValueExact();
	}

	/** 顯示用：捨入到元，HALF_UP（負數往遠離 0 的方向，和 Part A 收益率一致）。 */
	static BigDecimal toYuan(BigDecimal amount) {
		return amount.setScale(0, RoundingMode.HALF_UP);
	}

	/** 重播過程的狀態。每次 replay 都建立新的一份，不留任何狀態，同樣的輸入結果一定一樣。 */
	private static final class Ledger {

		private final String code;
		private long shares;
		/** 剩餘總成本（未捨入）。配股不改變它。 */
		private BigDecimal cost = BigDecimal.ZERO;
		/** 已實現損益（未捨入）。 */
		private BigDecimal realized = BigDecimal.ZERO;
		private BigDecimal cashDividends = BigDecimal.ZERO;
		private final List<DividendDetail> details = new ArrayList<>();
		/** 是否遇過「無法取得」而且可能有配股的事件；之後超賣時，錯誤訊息要提醒配股可能沒算進來。 */
		private boolean unavailableStockEventSeen;

		Ledger(String code) {
			this.code = code;
		}

		/**
		 * 同一天的所有除權息事件都用「當天開始時」的股數，配股最後才加：
		 * 同一天除權又除息時，現金股利要用配股前的股數（2,000 × 1.2，不是 2,020 × 1.2）。
		 */
		void applyDividendDay(List<DividendEventInput> dayEvents) {
			long eligible = shares;
			// 沒有持股就不是「持有期間」的事件，明細也不列
			if (eligible == 0) {
				return;
			}
			long addedShares = 0;
			for (DividendEventInput e : dayEvents) {
				if (e.source() == DividendSource.UNAVAILABLE) {
					// 不猜數字、不計入，但明細要讓使用者看到有這一筆
					details.add(new DividendDetail(e.exDate(), e.kind(), eligible, null, null, e.source()));
					if (e.kind() != DividendKind.CASH) {
						unavailableStockEventSeen = true;
					}
					continue;
				}
				BigDecimal cash = cashDividend(eligible, e.cashPerShare());
				long stock = stockDividend(eligible, e.stockPerThousand());
				cashDividends = cashDividends.add(cash);
				addedShares += stock;
				details.add(new DividendDetail(e.exDate(), e.kind(), eligible, cash, stock, e.source()));
			}
			shares += addedShares;
		}

		/** 買進：總成本 += 成交金額 + 手續費。 */
		void applyBuy(TradeInput t) {
			BigDecimal amount = tradeAmount(t.price(), t.shares());
			cost = cost.add(amount).add(ProfitCalculator.fee(amount));
			shares += t.shares();
		}

		/**
		 * 賣出：按比例扣成本，差額記為已實現損益。
		 *
		 * @param sameDayBuyAfter 同一天、排在這筆後面還有買進；只用在超賣時的錯誤訊息
		 */
		void applySell(TradeInput t, boolean sameDayBuyAfter) {
			// 每一筆賣出都檢查，所以「中間某個時間點超賣」也會被擋，即使最後總數是正的
			if (t.shares() > shares) {
				throw new OversellException(t.id(), t.date(), shares, t.shares(), unavailableStockEventSeen,
						sameDayBuyAfter);
			}
			BigDecimal amount = tradeAmount(t.price(), t.shares());
			BigDecimal fee = ProfitCalculator.fee(amount);
			BigDecimal tax = ProfitCalculator.transactionTax(code, amount);
			// 先乘再除，只在除法捨入一次；除不盡時（例如賣出 1/3）保留 10 位小數
			BigDecimal deductedCost = cost.multiply(BigDecimal.valueOf(t.shares()))
					.divide(BigDecimal.valueOf(shares), SCALE, RoundingMode.HALF_UP);
			realized = realized.add(amount.subtract(fee).subtract(tax).subtract(deductedCost));
			cost = cost.subtract(deductedCost);
			shares -= t.shares();
			// 股數歸零時成本強制歸零，避免小數誤差殘留到之後重新買回的持股
			if (shares == 0) {
				cost = BigDecimal.ZERO;
			}
		}

		HoldingReplayResult toResult(BigDecimal closePrice) {
			BigDecimal averageCost = shares == 0 ? null
					: cost.divide(BigDecimal.valueOf(shares), 2, RoundingMode.HALF_UP);
			BigDecimal realizedYuan = toYuan(realized);

			BigDecimal marketValue;
			BigDecimal unrealized;
			if (shares == 0) {
				// 手上沒有股票，不需要收盤價：市值、未實現都是 0，含息總報酬照樣算得出來
				marketValue = BigDecimal.ZERO;
				unrealized = BigDecimal.ZERO;
			} else if (closePrice == null) {
				marketValue = null;
				unrealized = null;
			} else {
				// 照 Part A：假設用收盤價全部賣出，扣掉賣出手續費和證交稅
				marketValue = tradeAmount(closePrice, shares);
				BigDecimal sellFee = ProfitCalculator.fee(marketValue);
				BigDecimal tax = ProfitCalculator.transactionTax(code, marketValue);
				unrealized = toYuan(marketValue.subtract(sellFee).subtract(tax).subtract(cost));
			}

			// 用捨入後的數字相加，畫面上的三個數字加起來才會剛好等於含息總報酬
			BigDecimal totalReturn = unrealized == null ? null : realizedYuan.add(unrealized).add(cashDividends);

			return new HoldingReplayResult(shares, toYuan(cost), averageCost, realizedYuan, marketValue, unrealized,
					cashDividends, totalReturn, details);
		}
	}
}
