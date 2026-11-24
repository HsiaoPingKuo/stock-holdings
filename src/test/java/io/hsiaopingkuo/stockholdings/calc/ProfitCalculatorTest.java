package io.hsiaopingkuo.stockholdings.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * 盈虧計算規則（第 4 題）。題目給的三個例子逐項檢查，再補上捨入、最低手續費的邊界情況。
 * 純計算，不連網、不用 Spring。
 */
class ProfitCalculatorTest {

	private static BigDecimal bd(String value) {
		return new BigDecimal(value);
	}

	/** 收益率要連小數位數一起比（9.36 和 9.360 不算一樣），金額用 compareTo 比數值就好。 */
	private static void assertRate(BigDecimal actual, String expected) {
		assertThat(actual).isEqualTo(bd(expected));
	}

	@Test
	void 題目例子_一般股票2330() {
		ProfitResult r = ProfitCalculator.calculate("2330", 1000, bd("1000.00"), bd("1100.00"));

		assertThat(r.buyAmount()).isEqualByComparingTo("1000000");
		assertThat(r.buyFee()).isEqualByComparingTo("1425");
		assertThat(r.sellAmount()).isEqualByComparingTo("1100000");
		// 1,567.5 無條件捨去
		assertThat(r.sellFee()).isEqualByComparingTo("1567");
		assertThat(r.transactionTax()).isEqualByComparingTo("3300");
		assertThat(r.cost()).isEqualByComparingTo("1001425");
		assertThat(r.marketValue()).isEqualByComparingTo("1100000");
		assertThat(r.profit()).isEqualByComparingTo("93708");
		assertRate(r.returnRate(), "9.36");
		assertThat(r.hasClosePrice()).isTrue();
	}

	@Test
	void 題目例子_ETF0050_稅率千分之一_虧損() {
		ProfitResult r = ProfitCalculator.calculate("0050", 2000, bd("150.00"), bd("145.50"));

		assertThat(r.buyAmount()).isEqualByComparingTo("300000");
		// 427.5 捨去
		assertThat(r.buyFee()).isEqualByComparingTo("427");
		assertThat(r.sellAmount()).isEqualByComparingTo("291000");
		// 414.675 捨去
		assertThat(r.sellFee()).isEqualByComparingTo("414");
		assertThat(r.transactionTax()).isEqualByComparingTo("291");
		assertThat(r.cost()).isEqualByComparingTo("300427");
		assertThat(r.marketValue()).isEqualByComparingTo("291000");
		assertThat(r.profit()).isEqualByComparingTo("-10132");
		assertRate(r.returnRate(), "-3.37");
	}

	@Test
	void 題目例子_2363_買賣手續費都低於20元() {
		ProfitResult r = ProfitCalculator.calculate("2363", 1000, bd("10.00"), bd("10.50"));

		// 14.25 → 14 → 最低 20
		assertThat(r.buyFee()).isEqualByComparingTo("20");
		// 14.9625 → 14 → 最低 20
		assertThat(r.sellFee()).isEqualByComparingTo("20");
		// 31.5 捨去
		assertThat(r.transactionTax()).isEqualByComparingTo("31");
		assertThat(r.cost()).isEqualByComparingTo("10020");
		assertThat(r.profit()).isEqualByComparingTo("429");
		assertRate(r.returnRate(), "4.28");
	}

	@Test
	void 只有買進手續費低於20元() {
		ProfitResult r = ProfitCalculator.calculate("2363", 1000, bd("14.00"), bd("15.00"));

		// 買進 14,000 × 0.1425% = 19.95 → 19 → 20；賣出 15,000 × 0.1425% = 21.375 → 21
		assertThat(r.buyFee()).isEqualByComparingTo("20");
		assertThat(r.sellFee()).isEqualByComparingTo("21");
		assertThat(r.transactionTax()).isEqualByComparingTo("45");
		assertThat(r.profit()).isEqualByComparingTo("914");
		// 914 ÷ 14,020 × 100 = 6.519…
		assertRate(r.returnRate(), "6.52");
	}

	@Test
	void 只有賣出手續費低於20元() {
		ProfitResult r = ProfitCalculator.calculate("2363", 1000, bd("15.00"), bd("14.00"));

		assertThat(r.buyFee()).isEqualByComparingTo("21");
		assertThat(r.sellFee()).isEqualByComparingTo("20");
		assertThat(r.transactionTax()).isEqualByComparingTo("42");
		assertThat(r.profit()).isEqualByComparingTo("-1083");
		// -1,083 ÷ 15,021 × 100 = -7.209…
		assertRate(r.returnRate(), "-7.21");
	}

	@Test
	void 收益率負數也是HALF_UP_往遠離0的方向進位() {
		// 題目的例子：-3.375 → -3.38
		assertRate(ProfitCalculator.returnRate(bd("-3375"), bd("100000")), "-3.38");
		// -0.125：HALF_UP 是 -0.13；如果誤用 HALF_EVEN 或 DOWN 會得到 -0.12，這個值才分得出來
		assertRate(ProfitCalculator.returnRate(bd("-125"), bd("100000")), "-0.13");
		// 正數對照
		assertRate(ProfitCalculator.returnRate(bd("125"), bd("100000")), "0.13");
	}

	@Test
	void 成本是0時收益率回0_00而不是除以0() {
		assertRate(ProfitCalculator.returnRate(BigDecimal.ZERO, BigDecimal.ZERO), "0.00");
	}

	@Test
	void 代號00開頭含英文字母也算ETF() {
		ProfitResult r = ProfitCalculator.calculate("00631L", 1000, bd("20.00"), bd("30.00"));

		// 30,000 × 0.1% = 30（一般股票會是 90）
		assertThat(r.transactionTax()).isEqualByComparingTo("30");
	}

	@Test
	void 只有一個0開頭不算ETF() {
		// 防止誤寫成 startsWith("0")
		assertThat(ProfitCalculator.isEtf("0050")).isTrue();
		assertThat(ProfitCalculator.isEtf("2330")).isFalse();
		assertThat(ProfitCalculator.isEtf("0123")).isFalse();
	}

	@Test
	void 沒有收盤價_只算買進那一邊_賣出與盈虧是null() {
		ProfitResult r = ProfitCalculator.calculate("2330", 1000, bd("1000.00"), null);

		assertThat(r.buyAmount()).isEqualByComparingTo("1000000");
		assertThat(r.buyFee()).isEqualByComparingTo("1425");
		assertThat(r.cost()).isEqualByComparingTo("1001425");
		assertThat(r.sellAmount()).isNull();
		assertThat(r.sellFee()).isNull();
		assertThat(r.transactionTax()).isNull();
		assertThat(r.marketValue()).isNull();
		assertThat(r.profit()).isNull();
		assertThat(r.returnRate()).isNull();
		assertThat(r.hasClosePrice()).isFalse();
	}

	@Test
	void 輸入不合理時丟IllegalArgumentException() {
		assertThatThrownBy(() -> ProfitCalculator.calculate(" ", 1000, bd("10"), bd("10")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ProfitCalculator.calculate("2330", 0, bd("10"), bd("10")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ProfitCalculator.calculate("2330", 1000, bd("0"), bd("10")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ProfitCalculator.calculate("2330", 1000, bd("10"), bd("-1")))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
