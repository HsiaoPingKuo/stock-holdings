package io.hsiaopingkuo.stockholdings.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import io.hsiaopingkuo.stockholdings.exception.TradeValidationException;

/** 交易日期、股數、價格的規則（第 6 題決定 9）。不碰資料庫，「今天」直接指定。 */
class TradeRulesTest {

	// 2026-11-24 是星期二
	private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

	@Test
	void 早於2021年被擋_2021年1月1日可以() {
		assertThatThrownBy(() -> TradeRules.checkDate(LocalDate.of(2020, 12, 31), TODAY, true))
				.isInstanceOf(TradeValidationException.class)
				.hasMessage("交易日期不能早於 2021-01-01");
		// 2021-01-01 是星期五，不會被週末規則擋到
		assertThatCode(() -> TradeRules.checkDate(LocalDate.of(2021, 1, 1), TODAY, true)).doesNotThrowAnyException();
	}

	@Test
	void 明天被擋_今天可以() {
		assertThatThrownBy(() -> TradeRules.checkDate(TODAY.plusDays(1), TODAY, true))
				.isInstanceOf(TradeValidationException.class)
				.hasMessage("交易日期不能是未來的日期（今天是 2026-11-24）");
		assertThatCode(() -> TradeRules.checkDate(TODAY, TODAY, true)).doesNotThrowAnyException();
	}

	@Test
	void 週六被擋_不檢查週末時可以() {
		LocalDate saturday = LocalDate.of(2026, 11, 21);

		assertThatThrownBy(() -> TradeRules.checkDate(saturday, TODAY, true))
				.isInstanceOf(TradeValidationException.class)
				.hasMessage("2026-11-21 是週末，不是交易日，請確認日期");
		// 推估的日期沒有被改動時，service 傳 false
		assertThatCode(() -> TradeRules.checkDate(saturday, TODAY, false)).doesNotThrowAnyException();
	}

	@Test
	void 股數要是正整數且不超過上限() {
		assertThatThrownBy(() -> TradeRules.checkShares(0)).hasMessage("股數必須是大於 0 的整數");
		assertThatThrownBy(() -> TradeRules.checkShares(1_000_000_001L)).hasMessage("股數不能超過 1,000,000,000 股");
		assertThatCode(() -> TradeRules.checkShares(1_000_000_000L)).doesNotThrowAnyException();
	}

	@Test
	void 價格大於0_最多兩位小數_不超過資料表上限() {
		assertThatThrownBy(() -> TradeRules.checkPrice(BigDecimal.ZERO)).hasMessage("價格必須大於 0");
		assertThatThrownBy(() -> TradeRules.checkPrice(new BigDecimal("10.555"))).hasMessage("價格最多兩位小數");
		assertThatThrownBy(() -> TradeRules.checkPrice(new BigDecimal("10000000000"))).hasMessage("價格不能超過 9,999,999,999.99");
		// 10.500 實際上只有兩位小數；上限本身可以
		assertThatCode(() -> TradeRules.checkPrice(new BigDecimal("10.500"))).doesNotThrowAnyException();
		assertThatCode(() -> TradeRules.checkPrice(new BigDecimal("9999999999.99"))).doesNotThrowAnyException();
	}
}
