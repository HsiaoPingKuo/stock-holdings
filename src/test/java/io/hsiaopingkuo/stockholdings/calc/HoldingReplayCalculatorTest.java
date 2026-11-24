package io.hsiaopingkuo.stockholdings.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 重播交易與除權息的計算核心（第 6 題設計決定 8）。純計算，不連網、不用 Spring。
 * 題目只給股數、沒給買進價的例子，買進價是測試自己選的，只用來驗算成本。
 */
class HoldingReplayCalculatorTest {

	private static final List<TradeInput> NO_TRADES = List.of();
	private static final List<DividendEventInput> NO_EVENTS = List.of();

	private static BigDecimal bd(String value) {
		return new BigDecimal(value);
	}

	private static TradeInput buy(long id, String date, long shares, String price) {
		return new TradeInput(id, LocalDate.parse(date), TradeSide.BUY, shares, bd(price));
	}

	private static TradeInput sell(long id, String date, long shares, String price) {
		return new TradeInput(id, LocalDate.parse(date), TradeSide.SELL, shares, bd(price));
	}

	private static DividendEventInput event(String exDate, DividendKind kind, String cash, String stock,
			DividendSource source) {
		return new DividendEventInput(LocalDate.parse(exDate), kind, cash == null ? null : bd(cash),
				stock == null ? null : bd(stock), source);
	}

	private static DividendEventInput officialCash(String exDate, String cash) {
		return event(exDate, DividendKind.CASH, cash, "0", DividendSource.OFFICIAL);
	}

	// ---------- 1. 題目第 12 節的例子 ----------

	@Test
	void 題目例子_2330除息4_5元_1500股領6750元() {
		HoldingReplayResult r = HoldingReplayCalculator.replay("2330",
				List.of(buy(1, "2025-06-10", 1500, "1000.00")), List.of(officialCash("2025-06-12", "4.5")), null);

		// 1,500 × 4.5
		assertThat(r.cumulativeCashDividend()).isEqualByComparingTo("6750");
		assertThat(r.totalShares()).isEqualTo(1500);
		// 1,500,000 + 2,137.5 捨去成 2,137
		assertThat(r.totalCost()).isEqualByComparingTo("1502137");
		assertThat(r.dividends()).containsExactly(new DividendDetail(LocalDate.parse("2025-06-12"),
				DividendKind.CASH, 1500, bd("6750"), 0L, DividendSource.OFFICIAL));
	}

	@Test
	void 題目例子_2884權息_2000股_現金2400_配股20_平均成本29_75() {
		HoldingReplayResult r = HoldingReplayCalculator.replay("2884",
				List.of(buy(1, "2025-07-01", 2000, "30.00")),
				List.of(event("2025-07-22", DividendKind.CASH_AND_STOCK, "1.2", "10", DividendSource.OFFICIAL)), null);

		assertThat(r.cumulativeCashDividend()).isEqualByComparingTo("2400");
		assertThat(r.totalShares()).isEqualTo(2020);
		// 60,000 + 85.5 捨去成 85；配股的成本是 0，總成本不變
		assertThat(r.totalCost()).isEqualByComparingTo("60085");
		// 60,085 ÷ 2,020 = 29.7450495… → 29.75（要連小數位數一起比）
		assertThat(r.averageCost()).isEqualTo(bd("29.75"));
		assertThat(r.dividends()).singleElement().satisfies(d -> {
			assertThat(d.eligibleShares()).isEqualTo(2000);
			assertThat(d.cashDividend()).isEqualByComparingTo("2400");
			assertThat(d.stockDividendShares()).isEqualTo(20L);
		});
	}

	@Test
	void 題目例子_2884權息_1550股_現金1860_配股15_5捨去成15() {
		HoldingReplayResult r = HoldingReplayCalculator.replay("2884",
				List.of(buy(1, "2025-07-01", 1550, "30.00")),
				List.of(event("2025-07-22", DividendKind.CASH_AND_STOCK, "1.2", "10", DividendSource.OFFICIAL)), null);

		assertThat(r.cumulativeCashDividend()).isEqualByComparingTo("1860");
		assertThat(r.dividends().get(0).stockDividendShares()).isEqualTo(15L);
		assertThat(r.totalShares()).isEqualTo(1565);
	}

	@Test
	void 題目例子_8069除息4_99013930_1000股_4990_139捨去成4990() {
		HoldingReplayResult r = HoldingReplayCalculator.replay("8069",
				List.of(buy(1, "2025-07-01", 1000, "300.00")), List.of(officialCash("2025-07-24", "4.99013930")),
				null);

		assertThat(r.cumulativeCashDividend()).isEqualByComparingTo("4990");
	}

	@Test
	void 平均成本剛好卡在5時用HALF_UP_10_025變10_03() {
		// 8,000 × 0.1425% = 11.4 → 最低 20；8,020 ÷ 800 = 10.025（用 HALF_EVEN 會得到 10.02）
		HoldingReplayResult r = HoldingReplayCalculator.replay("2363",
				List.of(buy(1, "2025-06-10", 800, "10.00")), NO_EVENTS, null);

		assertThat(r.totalCost()).isEqualByComparingTo("8020");
		assertThat(r.averageCost()).isEqualTo(bd("10.03"));
	}

	// ---------- 2. 零股成交金額 ----------

	@Test
	void 零股成交金額元以下捨去_7股乘10_55是73() {
		HoldingReplayResult r = HoldingReplayCalculator.replay("2363",
				List.of(buy(1, "2025-06-10", 7, "10.55")), NO_EVENTS, null);

		assertThat(HoldingReplayCalculator.tradeAmount(bd("10.55"), 7)).isEqualTo(bd("73"));
		// 73 + 手續費（0.104 → 0 → 最低 20）
		assertThat(r.totalCost()).isEqualByComparingTo("93");
		// 93 ÷ 7 = 13.2857…
		assertThat(r.averageCost()).isEqualTo(bd("13.29"));
	}

	// ---------- 3. 除權息當天買賣 ----------

	@Test
	void 除權息當天買進領不到_明細也不列() {
		HoldingReplayResult r = HoldingReplayCalculator.replay("2330",
				List.of(buy(1, "2025-06-12", 1000, "1000.00")), List.of(officialCash("2025-06-12", "4.5")), null);

		assertThat(r.cumulativeCashDividend()).isEqualByComparingTo("0");
		assertThat(r.dividends()).isEmpty();
		assertThat(r.totalShares()).isEqualTo(1000);
	}

	@Test
	void 除權息當天賣出領得到() {
		HoldingReplayResult r = HoldingReplayCalculator.replay("2330",
				List.of(buy(1, "2025-06-10", 1000, "1000.00"), sell(2, "2025-06-12", 1000, "1000.00")),
				List.of(officialCash("2025-06-12", "4.5")), bd("1100.00"));

		assertThat(r.cumulativeCashDividend()).isEqualByComparingTo("4500");
		assertThat(r.dividends()).singleElement().extracting(DividendDetail::eligibleShares).isEqualTo(1000L);
		// 賣出淨收 1,000,000 - 1,425 - 3,000 = 995,575；成本 1,001,425
		assertThat(r.realizedPnl()).isEqualByComparingTo("-5850");
		assertThat(r.totalShares()).isZero();
		assertThat(r.unrealizedPnl()).isEqualByComparingTo("0");
		// -5,850 + 0 + 4,500
		assertThat(r.totalReturn()).isEqualByComparingTo("-1350");
	}

	// ---------- 4. 超賣 ----------

	@Test
	void 同一天先輸入賣出再輸入買進_依id順序被當成超賣擋下() {
		List<TradeInput> trades = List.of(sell(1, "2025-06-10", 1000, "1000.00"),
				buy(2, "2025-06-10", 1000, "1000.00"));

		assertThatThrownBy(() -> HoldingReplayCalculator.replay("2330", trades, NO_EVENTS, null))
				.isInstanceOfSatisfying(OversellException.class, e -> {
					assertThat(e.getTradeId()).isEqualTo(1);
					assertThat(e.getHeldShares()).isZero();
					assertThat(e.isSameDayBuyAfter()).isTrue();
					assertThat(e.getMessage()).contains("同一天的交易依輸入順序計算，請先輸入買進，或修改日期");
				});
	}

	@Test
	void 同一天先賣光再買回_新買進的成本不混進前一輪() {
		// 朋友實測：1.0 轉入 3,000 股 → 同一天在 2.0 全部賣出 → 同一天用 App 1.0 買回 2,000 股
		List<TradeInput> sellOut = List.of(buy(1, "2025-06-10", 3000, "140.55"),
				sell(2, "2025-06-10", 3000, "146.00"));
		List<TradeInput> buyBack = new ArrayList<>(sellOut);
		buyBack.add(buy(3, "2025-06-10", 2000, "145.00"));

		HoldingReplayResult before = HoldingReplayCalculator.replay("0050", sellOut, NO_EVENTS, null);
		HoldingReplayResult r = HoldingReplayCalculator.replay("0050", buyBack, NO_EVENTS, null);

		// 成本 421,650 + 600 = 422,250；賣出淨收 438,000 - 624 - 438（ETF 0.1%）= 436,938
		assertThat(before.realizedPnl()).isEqualByComparingTo("14688");
		// 買回不影響之前那次賣出的已實現損益
		assertThat(r.realizedPnl()).isEqualByComparingTo(before.realizedPnl());
		assertThat(r.totalShares()).isEqualTo(2000);
		// 290,000 + 413 = 290,413；290,413 ÷ 2,000 = 145.2065 → 145.21（舊順序會算成 142.53）
		assertThat(r.totalCost()).isEqualByComparingTo("290413");
		assertThat(r.averageCost()).isEqualTo(bd("145.21"));
	}

	@Test
	void 中間某個時間點超賣要被擋_即使最後總數是正的() {
		List<TradeInput> trades = List.of(buy(1, "2025-06-02", 1000, "100.00"), sell(2, "2025-06-03", 1000, "100.00"),
				sell(3, "2025-06-04", 500, "100.00"), buy(4, "2025-06-05", 2000, "100.00"));

		assertThatThrownBy(() -> HoldingReplayCalculator.replay("2330", trades, NO_EVENTS, null))
				.isInstanceOfSatisfying(OversellException.class, e -> {
					assertThat(e.getTradeId()).isEqualTo(3);
					assertThat(e.getTradeDate()).isEqualTo(LocalDate.parse("2025-06-04"));
					assertThat(e.getHeldShares()).isZero();
					assertThat(e.getSellShares()).isEqualTo(500);
					assertThat(e.isMayMissStockDividend()).isFalse();
				});
	}

	// ---------- 5. 同一天除權又除息 ----------

	@Test
	void 同一天除權又除息_一筆權息_現金用配股前的股數() {
		HoldingReplayResult r = HoldingReplayCalculator.replay("2884",
				List.of(buy(1, "2025-07-01", 2000, "30.00")),
				List.of(event("2025-07-22", DividendKind.CASH_AND_STOCK, "1.2", "10", DividendSource.OFFICIAL)), null);

		// 2,000 × 1.2，不是 2,020 × 1.2 = 2,424
		assertThat(r.cumulativeCashDividend()).isEqualByComparingTo("2400");
		assertThat(r.totalShares()).isEqualTo(2020);
	}

	@Test
	void 同一天除權又除息_權息分成兩筆且權排在前面_現金仍用配股前的股數() {
		HoldingReplayResult r = HoldingReplayCalculator.replay("2884",
				List.of(buy(1, "2025-07-01", 2000, "30.00")),
				List.of(event("2025-07-22", DividendKind.STOCK, "0", "10", DividendSource.OFFICIAL),
						event("2025-07-22", DividendKind.CASH, "1.2", "0", DividendSource.OFFICIAL)),
				null);

		assertThat(r.cumulativeCashDividend()).isEqualByComparingTo("2400");
		assertThat(r.totalShares()).isEqualTo(2020);
		// 兩筆都用當天開始時的 2,000 股
		assertThat(r.dividends()).extracting(DividendDetail::eligibleShares).containsExactly(2000L, 2000L);
		assertThat(r.dividends()).extracting(DividendDetail::stockDividendShares).containsExactly(20L, 0L);
	}

	// ---------- 6. 除不盡與重算 ----------

	@Test
	void 賣出三分之一除不盡_保留10位小數_重算與打亂順序結果都一樣() {
		List<TradeInput> trades = List.of(buy(1, "2025-06-10", 3000, "20.00"), sell(2, "2025-06-20", 1000, "21.00"));

		HoldingReplayResult r = HoldingReplayCalculator.replay("2330", trades, NO_EVENTS, bd("21.00"));

		// 成本 60,000 + 85 = 60,085；扣掉 60,085 × 1,000 ÷ 3,000 = 20,028.3333333333
		// 賣出淨收 21,000 - 29 - 63 = 20,908；已實現 879.6666666667 → 880
		assertThat(r.realizedPnl()).isEqualByComparingTo("880");
		// 剩下 40,056.6666666667 → 40,057；平均成本 20.0283… → 20.03
		assertThat(r.totalCost()).isEqualByComparingTo("40057");
		assertThat(r.averageCost()).isEqualTo(bd("20.03"));
		// 收盤 21.00：42,000 - 59 - 126 = 41,815；41,815 - 40,056.6666666667 = 1,758.33 → 1,758
		assertThat(r.unrealizedPnl()).isEqualByComparingTo("1758");
		assertThat(r.totalReturn()).isEqualByComparingTo("2638");

		List<TradeInput> reversed = new ArrayList<>(trades);
		Collections.reverse(reversed);
		assertThat(HoldingReplayCalculator.replay("2330", trades, NO_EVENTS, bd("21.00"))).isEqualTo(r);
		assertThat(HoldingReplayCalculator.replay("2330", reversed, NO_EVENTS, bd("21.00"))).isEqualTo(r);
	}

	// ---------- 7. 全部賣出 ----------

	@Test
	void 全部賣出後總成本是0_平均成本是null_沒有收盤價時未實現也是0() {
		HoldingReplayResult r = HoldingReplayCalculator.replay("2330",
				List.of(buy(1, "2025-06-10", 2000, "20.00"), sell(2, "2025-06-20", 2000, "21.00")), NO_EVENTS, null);

		// 成本 40,000 + 57 = 40,057；賣出淨收 42,000 - 59 - 126 = 41,815
		assertThat(r.realizedPnl()).isEqualByComparingTo("1758");
		assertThat(r.totalShares()).isZero();
		assertThat(r.totalCost()).isEqualByComparingTo("0");
		assertThat(r.averageCost()).isNull();
		// 手上沒有股票，不需要收盤價
		assertThat(r.marketValue()).isEqualByComparingTo("0");
		assertThat(r.unrealizedPnl()).isEqualByComparingTo("0");
		assertThat(r.totalReturn()).isEqualByComparingTo("1758");
	}

	// ---------- 8. 無法取得 ----------

	@Test
	void 無法取得的事件不計入股利和配股_但明細裡有這一筆() {
		HoldingReplayResult r = HoldingReplayCalculator.replay("2884",
				List.of(buy(1, "2025-07-01", 2000, "30.00")),
				List.of(event("2025-07-22", DividendKind.CASH_AND_STOCK, null, null, DividendSource.UNAVAILABLE),
						// 以下兩筆是測試用的假設事件：確認官方、估算都會計入
						officialCash("2025-10-01", "0.5"),
						event("2025-12-01", DividendKind.CASH, "0.3", "0", DividendSource.ESTIMATED)),
				null);

		// 只有 2,000 × 0.5 + 2,000 × 0.3；配股沒有加
		assertThat(r.cumulativeCashDividend()).isEqualByComparingTo("1600");
		assertThat(r.totalShares()).isEqualTo(2000);
		assertThat(r.dividends()).hasSize(3);
		assertThat(r.dividends().get(0)).isEqualTo(new DividendDetail(LocalDate.parse("2025-07-22"),
				DividendKind.CASH_AND_STOCK, 2000, null, null, DividendSource.UNAVAILABLE));
		assertThat(r.dividends()).extracting(DividendDetail::source).containsExactly(DividendSource.UNAVAILABLE,
				DividendSource.OFFICIAL, DividendSource.ESTIMATED);
	}

	@Test
	void 無法取得的除權之後賣出配股股數_超賣並提醒配股可能未計入() {
		List<TradeInput> trades = List.of(buy(1, "2025-07-01", 2000, "30.00"), sell(2, "2025-08-01", 2020, "31.00"));
		List<DividendEventInput> events = List
				.of(event("2025-07-22", DividendKind.CASH_AND_STOCK, null, null, DividendSource.UNAVAILABLE));

		assertThatThrownBy(() -> HoldingReplayCalculator.replay("2884", trades, events, null))
				.isInstanceOfSatisfying(OversellException.class, e -> {
					assertThat(e.getTradeId()).isEqualTo(2);
					assertThat(e.getHeldShares()).isEqualTo(2000);
					assertThat(e.isMayMissStockDividend()).isTrue();
					assertThat(e.getMessage()).contains("配股可能未計入");
				});
	}

	// ---------- 9. 沒有收盤價 ----------

	@Test
	void 沒有收盤價時未實現損益和含息總報酬是null_已實現和股利照算() {
		HoldingReplayResult r = HoldingReplayCalculator.replay("2330",
				List.of(buy(1, "2025-06-10", 1000, "1000.00"), sell(2, "2025-06-20", 400, "1100.00")),
				List.of(officialCash("2025-06-12", "4.5")), null);

		assertThat(r.cumulativeCashDividend()).isEqualByComparingTo("4500");
		// 賣出淨收 440,000 - 627 - 1,320 = 438,053；扣掉 1,001,425 × 400 ÷ 1,000 = 400,570
		assertThat(r.realizedPnl()).isEqualByComparingTo("37483");
		assertThat(r.totalShares()).isEqualTo(600);
		assertThat(r.totalCost()).isEqualByComparingTo("600855");
		assertThat(r.marketValue()).isNull();
		assertThat(r.unrealizedPnl()).isNull();
		assertThat(r.totalReturn()).isNull();
	}

	// ---------- 10. 回歸：跟 Part A 一樣 ----------

	@Test
	void 只有一筆整張買進時_結果跟Part_A的ProfitCalculator一模一樣() {
		assertSameAsPartA("2330", 1000, "1000.00", "1100.00");
		assertSameAsPartA("0050", 2000, "150.00", "145.50");
		assertSameAsPartA("2363", 1000, "10.00", "10.50");
		assertSameAsPartA("2330", 1000, "1000.00", null);
	}

	@Test
	void Part_A的2330平均成本1001_425要是1001_43() {
		HoldingReplayResult r = HoldingReplayCalculator.replay("2330",
				List.of(buy(1, "2025-06-10", 1000, "1000.00")), NO_EVENTS, bd("1100.00"));

		// 剛好卡在 5，用 HALF_EVEN 會得到 1001.42
		assertThat(r.averageCost()).isEqualTo(bd("1001.43"));
	}

	private static void assertSameAsPartA(String code, long shares, String buyPrice, String closePrice) {
		BigDecimal close = closePrice == null ? null : bd(closePrice);
		ProfitResult partA = ProfitCalculator.calculate(code, shares, bd(buyPrice), close);
		HoldingReplayResult r = HoldingReplayCalculator.replay(code,
				List.of(buy(1, "2025-06-10", shares, buyPrice)), NO_EVENTS, close);

		assertThat(r.totalShares()).isEqualTo(shares);
		assertThat(r.totalCost()).isEqualByComparingTo(partA.cost());
		assertThat(r.realizedPnl()).isEqualByComparingTo("0");
		assertThat(r.cumulativeCashDividend()).isEqualByComparingTo("0");
		if (close == null) {
			assertThat(r.marketValue()).isNull();
			assertThat(r.unrealizedPnl()).isNull();
			assertThat(r.totalReturn()).isNull();
		} else {
			assertThat(r.marketValue()).isEqualByComparingTo(partA.marketValue());
			assertThat(r.unrealizedPnl()).isEqualByComparingTo(partA.profit());
			assertThat(r.totalReturn()).isEqualByComparingTo(partA.profit());
		}
	}

	// ---------- 其他規則 ----------

	@Test
	void 含息總報酬用捨入後的數字相加() {
		HoldingReplayResult r = HoldingReplayCalculator.replay("2330",
				List.of(buy(1, "2025-06-10", 2000, "20.00"), sell(2, "2025-06-20", 1000, "21.00")), NO_EVENTS,
				bd("21.00"));

		// 成本 40,057；賣出一半扣 20,028.5；淨收 20,908 → 已實現 879.5 → 880
		assertThat(r.realizedPnl()).isEqualByComparingTo("880");
		// 剩下的 20,028.5 → 20,029
		assertThat(r.totalCost()).isEqualByComparingTo("20029");
		// 收盤 21.00 淨收 20,908 - 20,028.5 = 879.5 → 880
		assertThat(r.unrealizedPnl()).isEqualByComparingTo("880");
		// 880 + 880 + 0；未捨入就相加會是 1,759
		assertThat(r.totalReturn()).isEqualByComparingTo("1760");
	}

	@Test
	void 沒有交易時全部是0() {
		HoldingReplayResult r = HoldingReplayCalculator.replay("2330", NO_TRADES,
				List.of(officialCash("2025-06-12", "4.5")), bd("1100.00"));

		assertThat(r.totalShares()).isZero();
		assertThat(r.totalCost()).isEqualByComparingTo("0");
		assertThat(r.averageCost()).isNull();
		assertThat(r.unrealizedPnl()).isEqualByComparingTo("0");
		assertThat(r.totalReturn()).isEqualByComparingTo("0");
		assertThat(r.dividends()).isEmpty();
	}

	@Test
	void 輸入不合理時丟IllegalArgumentException() {
		assertThatThrownBy(() -> HoldingReplayCalculator.replay(" ", NO_TRADES, NO_EVENTS, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> HoldingReplayCalculator.replay("2330", NO_TRADES, NO_EVENTS, bd("0")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> buy(1, "2025-06-10", 0, "10.00")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> buy(1, "2025-06-10", 1000, "0")).isInstanceOf(IllegalArgumentException.class);
		// 官方、估算的事件一定要有數字；只有「無法取得」可以是 null
		assertThatThrownBy(() -> event("2025-06-12", DividendKind.CASH, null, "0", DividendSource.OFFICIAL))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> event("2025-06-12", DividendKind.CASH, "-1", "0", DividendSource.ESTIMATED))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
