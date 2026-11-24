package io.hsiaopingkuo.stockholdings.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 總計規則（第 4 題）。單筆的數字由 ProfitCalculator 算，這裡用題目的例子組合。 */
class SummaryCalculatorTest {

	private static final ProfitResult TSMC = ProfitCalculator.calculate("2330", 1000, new BigDecimal("1000.00"),
			new BigDecimal("1100.00"));
	private static final ProfitResult ETF_0050 = ProfitCalculator.calculate("0050", 2000, new BigDecimal("150.00"),
			new BigDecimal("145.50"));
	/** 沒有收盤價：成本 1,000 × 10 × 1000 = 10,000,000 + 手續費 14,250 */
	private static final ProfitResult NO_CLOSE = ProfitCalculator.calculate("6488", 10000, new BigDecimal("1000.00"),
			null);

	@Test
	void 全部有收盤價_加總成本市值盈虧並重算收益率() {
		PortfolioSummary s = SummaryCalculator.summarize(List.of(TSMC, ETF_0050));

		// 1,001,425 + 300,427
		assertThat(s.totalCost()).isEqualByComparingTo("1301852");
		// 1,100,000 + 291,000
		assertThat(s.marketValue()).isEqualByComparingTo("1391000");
		// 93,708 - 10,132
		assertThat(s.profit()).isEqualByComparingTo("83576");
		// 83,576 ÷ 1,301,852 × 100 = 6.4197…；不是兩筆收益率相加（9.36 - 3.37）
		assertThat(s.returnRate()).isEqualTo(new BigDecimal("6.42"));
		assertThat(s.holdingCount()).isEqualTo(2);
		assertThat(s.missingCloseCount()).isZero();
		assertThat(s.allMissingClose()).isFalse();
	}

	@Test
	void 有一筆沒有收盤價_不算進市值盈虧_但算進總成本() {
		PortfolioSummary s = SummaryCalculator.summarize(List.of(TSMC, ETF_0050, NO_CLOSE));

		// 1,301,852 + 10,014,250
		assertThat(s.totalCost()).isEqualByComparingTo("11316102");
		assertThat(s.marketValue()).isEqualByComparingTo("1391000");
		assertThat(s.profit()).isEqualByComparingTo("83576");
		// 分母包含沒有收盤價那筆的成本：83,576 ÷ 11,316,102 × 100 = 0.7385…
		assertThat(s.returnRate()).isEqualTo(new BigDecimal("0.74"));
		assertThat(s.holdingCount()).isEqualTo(3);
		assertThat(s.missingCloseCount()).isEqualTo(1);
		assertThat(s.allMissingClose()).isFalse();
	}

	@Test
	void 沒有任何持股_總成本0_收益率0_00_不會出錯() {
		PortfolioSummary s = SummaryCalculator.summarize(List.of());

		assertThat(s.totalCost()).isEqualByComparingTo("0");
		assertThat(s.marketValue()).isEqualByComparingTo("0");
		assertThat(s.profit()).isEqualByComparingTo("0");
		assertThat(s.returnRate()).isEqualTo(new BigDecimal("0.00"));
		assertThat(s.holdingCount()).isZero();
		assertThat(s.missingCloseCount()).isZero();
		// 沒有持股不算「全部都沒有收盤價」，網頁照常顯示 0
		assertThat(s.allMissingClose()).isFalse();
	}

	@Test
	void 全部都沒有收盤價_數字照規格是0_並標記allMissingClose() {
		PortfolioSummary s = SummaryCalculator.summarize(List.of(NO_CLOSE));

		assertThat(s.totalCost()).isEqualByComparingTo("10014250");
		// 計算類別照 App API 規格回 0，不回 null；「還算不出來」交給網頁用 allMissingClose 判斷
		assertThat(s.marketValue()).isEqualByComparingTo("0");
		assertThat(s.profit()).isEqualByComparingTo("0");
		assertThat(s.returnRate()).isEqualTo(new BigDecimal("0.00"));
		assertThat(s.missingCloseCount()).isEqualTo(1);
		assertThat(s.allMissingClose()).isTrue();
	}
}
