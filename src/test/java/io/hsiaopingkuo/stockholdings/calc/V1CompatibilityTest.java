package io.hsiaopingkuo.stockholdings.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

/** App 1.0 相容規則（第 6 題決定 2、3）：純計算，不碰資料庫。 */
class V1CompatibilityTest {

	private static final LocalDate NOV_20 = LocalDate.of(2026, 11, 20);
	private static final BigDecimal AVERAGE = new BigDecimal("1001.42");

	private static TradeInput buy(long id, long shares, String price) {
		return new TradeInput(id, NOV_20, TradeSide.BUY, shares, new BigDecimal(price));
	}

	private static TradeInput sell(long id, long shares, String price) {
		return new TradeInput(id, NOV_20.plusDays(1), TradeSide.SELL, shares, new BigDecimal(price));
	}

	@Test
	void 張數是股數除以1000捨去_零股另外算() {
		assertThat(V1Compatibility.lots(1500)).isEqualTo(1);
		assertThat(V1Compatibility.oddShares(1500)).isEqualTo(500);
		assertThat(V1Compatibility.lots(300)).isZero();
		assertThat(V1Compatibility.oddShares(300)).isEqualTo(300);
		assertThat(V1Compatibility.lots(2000)).isEqualTo(2);
		assertThat(V1Compatibility.oddShares(2000)).isZero();
		// 超過 1.0 上限 999 張也照實回傳（2.0 可以買超過 999 張）
		assertThat(V1Compatibility.lots(1_234_000)).isEqualTo(1234);
	}

	@Test
	void 名稱_有零股時加註_整張時不變() {
		assertThat(V1Compatibility.apiName("台積電", 1500)).isEqualTo("台積電【請更新 App 2.0：含零股 500 股】");
		assertThat(V1Compatibility.apiName("台積電", 300)).isEqualTo("台積電【請更新 App 2.0：含零股 300 股】");
		assertThat(V1Compatibility.apiName("台積電", 2000)).isEqualTo("台積電");
	}

	@Test
	void 單筆整張買進且股數沒變_可以用1點0修改_buy_price是成交價() {
		List<TradeInput> trades = List.of(buy(1, 2000, "980.50"));

		assertThat(V1Compatibility.isV1Editable(trades, 2000)).isTrue();
		assertThat(V1Compatibility.buyPrice(trades, 2000, AVERAGE)).isEqualByComparingTo("980.50");
	}

	@Test
	void 多筆交易_不能用1點0修改_buy_price是平均成本() {
		List<TradeInput> twoBuys = List.of(buy(1, 1000, "1000.00"), buy(2, 1000, "1010.00"));
		List<TradeInput> buyThenSell = List.of(buy(1, 2000, "1000.00"), sell(2, 1000, "1100.00"));

		assertThat(V1Compatibility.isV1Editable(twoBuys, 2000)).isFalse();
		assertThat(V1Compatibility.buyPrice(twoBuys, 2000, AVERAGE)).isEqualByComparingTo(AVERAGE);
		assertThat(V1Compatibility.isV1Editable(buyThenSell, 1000)).isFalse();
	}

	@Test
	void 單筆零股買進_不能用1點0修改() {
		List<TradeInput> trades = List.of(buy(1, 1500, "1000.00"));

		assertThat(V1Compatibility.isV1Editable(trades, 1500)).isFalse();
		assertThat(V1Compatibility.buyPrice(trades, 1500, AVERAGE)).isEqualByComparingTo(AVERAGE);
	}

	@Test
	void 配過股_股數和那筆買進不同_不能用1點0修改() {
		// 買 1,000 股，配股後變 2,000 股：整張，但分不出是「現在有 2 張」還是「當初買 2 張」
		List<TradeInput> trades = List.of(buy(1, 1000, "30.00"));

		assertThat(V1Compatibility.isV1Editable(trades, 2000)).isFalse();
		assertThat(V1Compatibility.buyPrice(trades, 2000, new BigDecimal("15.02"))).isEqualByComparingTo("15.02");
	}

	@Test
	void 只有一筆賣出_不能用1點0修改() {
		assertThat(V1Compatibility.isV1Editable(List.of(sell(1, 1000, "1000.00")), 0)).isFalse();
	}
}
