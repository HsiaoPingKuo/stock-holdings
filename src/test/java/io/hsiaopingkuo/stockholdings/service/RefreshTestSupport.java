package io.hsiaopingkuo.stockholdings.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import io.hsiaopingkuo.stockholdings.config.QuoteProperties;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.quote.BatchQuoteResult;
import io.hsiaopingkuo.stockholdings.quote.MarketCloseBatchClient;
import io.hsiaopingkuo.stockholdings.quote.StockLookup;
import io.hsiaopingkuo.stockholdings.quote.StockLookupClient;

/** 排程相關測試共用的假物件：可撥的時鐘、記錄請求的假 client、不真的等待的 Pause。 */
final class RefreshTestSupport {

	static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

	/** 和 application.properties 相同的設定。 */
	static final QuoteProperties.Refresh SETTINGS = new QuoteProperties.Refresh(true, LocalTime.of(13, 40),
			LocalTime.of(14, 30), LocalTime.of(15, 40), Duration.ofSeconds(60), Duration.ofSeconds(5),
			Duration.ofMinutes(10), Duration.ofMillis(3200), Duration.ofMillis(5800), Duration.ofMinutes(30), 10);

	private RefreshTestSupport() {
	}

	static QuoteProperties propertiesWith(QuoteProperties.Refresh refresh) {
		return new QuoteProperties(null, null, null, null, null, null, null, 0, null, null, refresh, null);
	}

	/** 可以手動往前撥的時鐘，時區固定台北。 */
	static final class MutableClock extends Clock {
		private Instant now;

		MutableClock(LocalDateTime taipeiTime) {
			this.now = taipeiTime.atZone(TAIPEI).toInstant();
		}

		MutableClock(Instant instant) {
			this.now = instant;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return TAIPEI;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	/** 不真的等，只把要等多久記下來。 */
	static final class RecordingPause implements Pause {
		final List<Duration> waits = new ArrayList<>();

		@Override
		public void sleep(Duration duration) {
			waits.add(duration);
		}
	}

	/** 假的批次 client：依日期回結果，並記下查過哪些日期。 */
	static final class FakeBatchClient implements MarketCloseBatchClient {
		private final Market market;
		Function<LocalDate, BatchQuoteResult> answer;
		final List<LocalDate> asked = new ArrayList<>();

		FakeBatchClient(Market market, Function<LocalDate, BatchQuoteResult> answer) {
			this.market = market;
			this.answer = answer;
		}

		@Override
		public Market market() {
			return market;
		}

		@Override
		public BatchQuoteResult fetchDay(LocalDate date) {
			asked.add(date);
			return answer.apply(date);
		}
	}

	/** 假的逐檔 client：依代號回結果，並記下查過哪些代號（同一代號查兩個月會記兩次）。 */
	static final class FakeStockClient implements StockLookupClient {
		private final Market market;
		Function<String, StockLookup> answer;
		final List<String> asked = new ArrayList<>();

		FakeStockClient(Market market, Function<String, StockLookup> answer) {
			this.market = market;
			this.answer = answer;
		}

		@Override
		public Market market() {
			return market;
		}

		@Override
		public StockLookup lookupMonth(String code, LocalDate dayInMonth) {
			asked.add(code);
			return answer.apply(code);
		}
	}
}
