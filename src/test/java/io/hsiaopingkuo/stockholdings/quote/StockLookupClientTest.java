package io.hsiaopingkuo.stockholdings.quote;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.quote.StockLookup.Status;

/** lookupLatest：這個月查不到、或整月沒成交時，往上個月找。用假 client，依「查哪個月」回固定結果。 */
class StockLookupClientTest {

	private static final LocalDate DEC_02 = LocalDate.of(2026, 12, 2);
	private static final LocalDate NOV_02 = LocalDate.of(2026, 11, 2);

	/** 依月份回結果，並記下查過哪些月份。 */
	private static final class FakeClient implements StockLookupClient {
		private final Map<LocalDate, StockLookup> answers;
		private final List<LocalDate> asked = new ArrayList<>();

		FakeClient(Map<LocalDate, StockLookup> answers) {
			this.answers = answers;
		}

		@Override
		public Market market() {
			return Market.TWSE;
		}

		@Override
		public StockLookup lookupMonth(String code, LocalDate dayInMonth) {
			asked.add(dayInMonth);
			return answers.getOrDefault(dayInMonth, StockLookup.unavailable(Market.TWSE));
		}
	}

	@Test
	void 這個月有成交_只查這個月() {
		FakeClient client = new FakeClient(Map.of(
				DEC_02, StockLookup.found(Market.TWSE, "台積電", DEC_02, new BigDecimal("2050.00"))));

		StockLookup result = client.lookupLatest("2330", DEC_02);

		assertThat(result.closePrice()).isEqualByComparingTo("2050.00");
		assertThat(client.asked).containsExactly(DEC_02);
	}

	@Test
	void 這個月每一天都沒有成交_收盤價往上個月找_最近交易日仍是這個月() {
		FakeClient client = new FakeClient(Map.of(
				DEC_02, StockLookup.found(Market.TWSE, "測試股", DEC_02, null, null),
				NOV_02, StockLookup.found(Market.TWSE, "測試股", LocalDate.of(2026, 11, 30), new BigDecimal("12.30"),
						LocalDate.of(2026, 11, 27))));

		StockLookup result = client.lookupLatest("1234", DEC_02);

		assertThat(result.status()).isEqualTo(Status.FOUND);
		assertThat(result.tradeDate()).isEqualTo(DEC_02);
		assertThat(result.closePrice()).isEqualByComparingTo("12.30");
		assertThat(result.closeDate()).isEqualTo(LocalDate.of(2026, 11, 27));
		assertThat(client.asked).containsExactly(DEC_02, NOV_02);
	}

	@Test
	void 這個月和上個月都沒有成交_收盤價是null但仍是FOUND() {
		FakeClient client = new FakeClient(Map.of(
				DEC_02, StockLookup.found(Market.TWSE, "測試股", DEC_02, null, null),
				NOV_02, StockLookup.found(Market.TWSE, "測試股", LocalDate.of(2026, 11, 30), null, null)));

		StockLookup result = client.lookupLatest("1234", DEC_02);

		assertThat(result.status()).isEqualTo(Status.FOUND);
		assertThat(result.tradeDate()).isEqualTo(DEC_02);
		assertThat(result.closePrice()).isNull();
	}

	@Test
	void 這個月還沒有交易日_改用上個月的結果() {
		FakeClient client = new FakeClient(Map.of(
				DEC_02, StockLookup.notFound(Market.TWSE),
				NOV_02, StockLookup.found(Market.TWSE, "台積電", LocalDate.of(2026, 11, 30), new BigDecimal("2040.00"))));

		StockLookup result = client.lookupLatest("2330", DEC_02);

		assertThat(result.tradeDate()).isEqualTo(LocalDate.of(2026, 11, 30));
		assertThat(result.closePrice()).isEqualByComparingTo("2040.00");
	}

	@Test
	void 這個月查不了_不往上個月找() {
		FakeClient client = new FakeClient(Map.of());

		StockLookup result = client.lookupLatest("2330", DEC_02);

		assertThat(result.status()).isEqualTo(Status.UNAVAILABLE);
		assertThat(client.asked).containsExactly(DEC_02);
	}
}
