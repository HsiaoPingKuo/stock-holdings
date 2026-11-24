package io.hsiaopingkuo.stockholdings.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 股利資料狀態的規則（第 6 題設計決定 7-D）：純計算，不碰資料庫。 */
class DividendStatusRuleTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);
	private static final LocalDate JULY_2025 = LocalDate.of(2025, 7, 1);

	@Test
	void 持有期間有一年還沒抓過_待更新() {
		assertThat(DividendStatusRule.status(JULY_2025, TODAY, List.of(Map.of(2026, false)), false))
				.isEqualTo(DividendDataStatus.PENDING);
	}

	@Test
	void 過去的年份抓過但沒抓完_待更新() {
		assertThat(DividendStatusRule.status(JULY_2025, TODAY, List.of(Map.of(2025, false, 2026, false)), false))
				.isEqualTo(DividendDataStatus.PENDING);
	}

	@Test
	void 過去的年份抓完_今年抓過一次_完整() {
		assertThat(DividendStatusRule.status(JULY_2025, TODAY, List.of(Map.of(2025, true, 2026, false)), false))
				.isEqualTo(DividendDataStatus.COMPLETE);
	}

	@Test
	void 第一筆交易之前的年份不用抓() {
		// 2025 沒抓過也不影響 2026 才買的持股
		assertThat(DividendStatusRule.status(LocalDate.of(2026, 3, 2), TODAY, List.of(Map.of(2026, false)), false))
				.isEqualTo(DividendDataStatus.COMPLETE);
	}

	@Test
	void 年份都抓了_但有明細待查_待更新() {
		assertThat(DividendStatusRule.status(JULY_2025, TODAY, List.of(Map.of(2025, true, 2026, false)), true))
				.isEqualTo(DividendDataStatus.PENDING);
	}

	@Test
	void 要看兩個市場時_其中一個還沒抓_待更新() {
		assertThat(DividendStatusRule.status(JULY_2025, TODAY,
				List.of(Map.of(2025, true, 2026, false), Map.of(2025, true)), false))
				.isEqualTo(DividendDataStatus.PENDING);
	}

	@Test
	void 沒有交易_完整() {
		assertThat(DividendStatusRule.status(null, TODAY, List.of(Map.of()), false))
				.isEqualTo(DividendDataStatus.COMPLETE);
	}
}
