package io.hsiaopingkuo.stockholdings.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.exception.MarketUnavailableException;
import io.hsiaopingkuo.stockholdings.exception.StockCodeNotFoundException;
import io.hsiaopingkuo.stockholdings.quote.StockLookup.Status;
import tools.jackson.databind.json.JsonMapper;

/**
 * 合併兩個市場的結果。
 * 大部分測試用「真的 client + MockRestServiceServer + 實測 JSON」，連網址和月初重查都一起驗證；
 * 只有「整體逾時」這種很難用假伺服器做出來的情況，才用自己寫的假 client。
 */
class MarketResolverTest {

	private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
	private static final String TWSE = "https://www.twse.com.tw";
	private static final String TPEX = "https://www.tpex.org.tw";
	private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(8);
	private static final Duration CACHE_TTL = Duration.ofMinutes(10);

	private final JsonMapper jsonMapper = JsonMapper.builder().build();
	private MockRestServiceServer twseServer;
	private MockRestServiceServer tpexServer;
	private MarketResolver resolver;

	@AfterEach
	void closeResolver() {
		if (resolver != null) {
			resolver.close();
		}
	}

	/** 建立「真的 client 接假伺服器」的 resolver；today 是台北時間的今天。 */
	private MarketResolver resolverWithMockServers(LocalDate today) {
		RestClient.Builder twseBuilder = RestClient.builder().baseUrl(TWSE);
		twseServer = MockRestServiceServer.bindTo(twseBuilder).build();
		RestClient.Builder tpexBuilder = RestClient.builder().baseUrl(TPEX);
		tpexServer = MockRestServiceServer.bindTo(tpexBuilder).build();
		resolver = new MarketResolver(
				new TwseStockDayClient(twseBuilder.build(), jsonMapper),
				new TpexTradingStockClient(tpexBuilder.build(), jsonMapper),
				clockAt(today), LOOKUP_TIMEOUT, CACHE_TTL);
		return resolver;
	}

	/** 台北時間該日下午 2 點。 */
	private static Clock clockAt(LocalDate day) {
		return Clock.fixed(ZonedDateTime.of(day.atTime(14, 0), TAIPEI).toInstant(), TAIPEI);
	}

	private static String twseUrl(String date, String code) {
		return TWSE + "/rwd/zh/afterTrading/STOCK_DAY?date=" + date + "&stockNo=" + code + "&response=json";
	}

	private static String tpexUrl(String date, String code) {
		return TPEX + "/www/zh-tw/afterTrading/tradingStock?code=" + code + "&date=" + date + "&response=json";
	}

	private static ClassPathResource json(String file) {
		return new ClassPathResource("quotes/" + file);
	}

	@Test
	void 代號2330_判斷為上市且帶回名稱收盤價日期() {
		MarketResolver resolver = resolverWithMockServers(LocalDate.of(2026, 11, 23));
		twseServer.expect(requestTo(twseUrl("20261123", "2330")))
				.andRespond(withSuccess(json("twse-stock-day-2330.json"), MediaType.APPLICATION_JSON));
		tpexServer.expect(requestTo(tpexUrl("2026/11/23", "2330")))
				.andRespond(withSuccess(json("tpex-trading-stock-2330-not-found.json"), MediaType.APPLICATION_JSON));
		// 櫃買這個月查不到，會再查上個月
		tpexServer.expect(requestTo(tpexUrl("2026/10/23", "2330")))
				.andRespond(withSuccess(json("tpex-trading-stock-2330-not-found.json"), MediaType.APPLICATION_JSON));

		StockLookup result = resolver.resolve("2330");

		assertThat(result.market()).isEqualTo(Market.TWSE);
		assertThat(result.name()).isEqualTo("台積電");
		assertThat(result.closePrice()).isEqualByComparingTo("2030.00");
		assertThat(result.tradeDate()).isEqualTo(LocalDate.of(2026, 11, 23));
		twseServer.verify();
		tpexServer.verify();
	}

	@Test
	void 代號6488_判斷為上櫃() {
		MarketResolver resolver = resolverWithMockServers(LocalDate.of(2026, 11, 24));
		twseServer.expect(requestTo(twseUrl("20261124", "6488")))
				.andRespond(withSuccess(json("twse-stock-day-6488-not-found.json"), MediaType.APPLICATION_JSON));
		twseServer.expect(requestTo(twseUrl("20261024", "6488")))
				.andRespond(withSuccess(json("twse-stock-day-6488-not-found.json"), MediaType.APPLICATION_JSON));
		tpexServer.expect(requestTo(tpexUrl("2026/11/24", "6488")))
				.andRespond(withSuccess(json("tpex-trading-stock-6488.json"), MediaType.APPLICATION_JSON));

		StockLookup result = resolver.resolve("6488");

		assertThat(result.market()).isEqualTo(Market.TPEX);
		assertThat(result.name()).isEqualTo("環球晶");
		assertThat(result.closePrice()).isEqualByComparingTo("460.50");
	}

	@Test
	void 代號9999_兩邊這個月和上個月都查不到_代號不存在() {
		MarketResolver resolver = resolverWithMockServers(LocalDate.of(2026, 11, 24));
		for (String date : new String[] { "20261124", "20261024" }) {
			twseServer.expect(requestTo(twseUrl(date, "9999")))
					.andRespond(withSuccess(json("twse-stock-day-9999-not-found.json"), MediaType.APPLICATION_JSON));
		}
		for (String date : new String[] { "2026/11/24", "2026/10/24" }) {
			// 櫃買「查不到」的格式和代號無關（data 是空陣列），借用 2330 的實測回應
			tpexServer.expect(requestTo(tpexUrl(date, "9999")))
					.andRespond(withSuccess(json("tpex-trading-stock-2330-not-found.json"), MediaType.APPLICATION_JSON));
		}

		assertThatThrownBy(() -> resolver.resolve("9999"))
				.isInstanceOf(StockCodeNotFoundException.class)
				.hasMessage("查無此股票代號：9999（上市、上櫃都找不到）");
		twseServer.verify();
		tpexServer.verify();
	}

	@Test
	void 月初_這個月還沒有交易日_改查上個月仍然查得到() {
		// 12/1：證交所 12 月回「沒有符合條件的資料」，11 月才有 2330 的資料
		MarketResolver resolver = resolverWithMockServers(LocalDate.of(2026, 12, 1));
		twseServer.expect(requestTo(twseUrl("20261201", "2330")))
				.andRespond(withSuccess(json("twse-stock-day-9999-not-found.json"), MediaType.APPLICATION_JSON));
		twseServer.expect(requestTo(twseUrl("20261101", "2330")))
				.andRespond(withSuccess(json("twse-stock-day-2330.json"), MediaType.APPLICATION_JSON));
		for (String date : new String[] { "2026/12/01", "2026/11/01" }) {
			tpexServer.expect(requestTo(tpexUrl(date, "2330")))
					.andRespond(withSuccess(json("tpex-trading-stock-2330-not-found.json"), MediaType.APPLICATION_JSON));
		}

		StockLookup result = resolver.resolve("2330");

		assertThat(result.status()).isEqualTo(Status.FOUND);
		assertThat(result.market()).isEqualTo(Market.TWSE);
		assertThat(result.tradeDate()).isEqualTo(LocalDate.of(2026, 11, 23));
		twseServer.verify();
		tpexServer.verify();
	}

	@Test
	void 一邊逾時另一邊查不到_無法確認() {
		MarketResolver resolver = resolverWithMockServers(LocalDate.of(2026, 11, 24));
		// 證交所逾時：只會送一次，不會再查上個月（查不了不等於查不到）
		twseServer.expect(requestTo(twseUrl("20261124", "9999")))
				.andRespond(withException(new SocketTimeoutException("Read timed out")));
		for (String date : new String[] { "2026/11/24", "2026/10/24" }) {
			tpexServer.expect(requestTo(tpexUrl(date, "9999")))
					.andRespond(withSuccess(json("tpex-trading-stock-2330-not-found.json"), MediaType.APPLICATION_JSON));
		}

		assertThatThrownBy(() -> resolver.resolve("9999"))
				.isInstanceOf(MarketUnavailableException.class)
				.hasMessage("暫時無法連線官方網站確認代號，請稍後再試");
		twseServer.verify();
		tpexServer.verify();
	}

	@Test
	void 一邊回傳HTML另一邊查到_以查到的為準() {
		MarketResolver resolver = resolverWithMockServers(LocalDate.of(2026, 11, 23));
		twseServer.expect(requestTo(twseUrl("20261123", "6488")))
				.andRespond(withSuccess("<html>blocked</html>", MediaType.TEXT_HTML));
		tpexServer.expect(requestTo(tpexUrl("2026/11/23", "6488")))
				.andRespond(withSuccess(json("tpex-trading-stock-6488.json"), MediaType.APPLICATION_JSON));

		assertThat(resolver.resolve("6488").market()).isEqualTo(Market.TPEX);
	}

	// ===== 以下用假 client：直接控制每一邊回什麼、花多久 =====

	private static StockLookupClient fakeClient(Market market, BiFunction<String, LocalDate, StockLookup> answer) {
		return new StockLookupClient() {
			@Override
			public Market market() {
				return market;
			}

			@Override
			public StockLookup lookupMonth(String code, LocalDate dayInMonth) {
				return answer.apply(code, dayInMonth);
			}
		};
	}

	private static StockLookup found(Market market) {
		return StockLookup.found(market, "測試", LocalDate.of(2026, 11, 23), new BigDecimal("10.00"));
	}

	@Test
	void 整體超過上限_還沒回來的一方視為無法確認_不會一直等() {
		CountDownLatch never = new CountDownLatch(1);
		StockLookupClient stuckTwse = fakeClient(Market.TWSE, (code, day) -> {
			try {
				never.await(); // 模擬官方一直不回應
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return StockLookup.unavailable(Market.TWSE);
		});
		StockLookupClient tpexNotFound = fakeClient(Market.TPEX, (code, day) -> StockLookup.notFound(Market.TPEX));
		resolver = new MarketResolver(stuckTwse, tpexNotFound, clockAt(LocalDate.of(2026, 11, 24)),
				Duration.ofMillis(200), CACHE_TTL);

		long start = System.nanoTime();
		assertThatThrownBy(() -> resolver.resolve("9999")).isInstanceOf(MarketUnavailableException.class);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
	}

	@Test
	void 兩邊都查到_以證交所為準() {
		resolver = new MarketResolver(
				fakeClient(Market.TWSE, (code, day) -> found(Market.TWSE)),
				fakeClient(Market.TPEX, (code, day) -> found(Market.TPEX)),
				clockAt(LocalDate.of(2026, 11, 24)), LOOKUP_TIMEOUT, CACHE_TTL);

		assertThat(resolver.resolve("1234").market()).isEqualTo(Market.TWSE);
	}

	@Test
	void client意外丟出例外_當成無法確認而不是整頁錯誤() {
		resolver = new MarketResolver(
				fakeClient(Market.TWSE, (code, day) -> {
					throw new IllegalStateException("模擬程式錯誤");
				}),
				fakeClient(Market.TPEX, (code, day) -> StockLookup.notFound(Market.TPEX)),
				clockAt(LocalDate.of(2026, 11, 24)), LOOKUP_TIMEOUT, CACHE_TTL);

		assertThatThrownBy(() -> resolver.resolve("1234")).isInstanceOf(MarketUnavailableException.class);
	}

	// ===== 快取：輸入代號時的即時提示和按下新增共用結果 =====

	/** 可以手動往前撥的時鐘，用來測「10 分鐘後過期」。 */
	private static final class MutableClock extends Clock {
		private Instant now;

		MutableClock(Instant start) {
			this.now = start;
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

	/** 會記錄被呼叫幾次的假 client。 */
	private static StockLookupClient countingClient(Market market, AtomicInteger calls, StockLookup answer) {
		return fakeClient(market, (code, day) -> {
			calls.incrementAndGet();
			return answer;
		});
	}

	private MutableClock clock;

	private MarketResolver resolverWithCounting(AtomicInteger twseCalls, StockLookup twseAnswer,
			AtomicInteger tpexCalls, StockLookup tpexAnswer) {
		clock = new MutableClock(clockAt(LocalDate.of(2026, 11, 24)).instant());
		resolver = new MarketResolver(
				countingClient(Market.TWSE, twseCalls, twseAnswer),
				countingClient(Market.TPEX, tpexCalls, tpexAnswer),
				clock, LOOKUP_TIMEOUT, CACHE_TTL);
		return resolver;
	}

	@Test
	void 查到之後10分鐘內再查同一代號_不再呼叫官方() {
		AtomicInteger twseCalls = new AtomicInteger();
		AtomicInteger tpexCalls = new AtomicInteger();
		MarketResolver resolver = resolverWithCounting(twseCalls, found(Market.TWSE),
				tpexCalls, StockLookup.notFound(Market.TPEX));

		resolver.resolve("2330");
		int twseAfterFirst = twseCalls.get();
		int tpexAfterFirst = tpexCalls.get();
		clock.advance(Duration.ofMinutes(9));
		StockLookup second = resolver.resolve("2330");

		assertThat(second.market()).isEqualTo(Market.TWSE);
		assertThat(twseCalls.get()).isEqualTo(twseAfterFirst);
		assertThat(tpexCalls.get()).isEqualTo(tpexAfterFirst);
	}

	@Test
	void 超過10分鐘_重新查官方() {
		AtomicInteger twseCalls = new AtomicInteger();
		AtomicInteger tpexCalls = new AtomicInteger();
		MarketResolver resolver = resolverWithCounting(twseCalls, found(Market.TWSE),
				tpexCalls, StockLookup.notFound(Market.TPEX));

		resolver.resolve("2330");
		clock.advance(Duration.ofMinutes(10));
		resolver.resolve("2330");

		// 證交所這個月就查到，每次只呼叫一次；兩次查詢共 2 次
		assertThat(twseCalls.get()).isEqualTo(2);
	}

	@Test
	void 確定查不到也會記住_期間內直接回查無此代號() {
		AtomicInteger twseCalls = new AtomicInteger();
		AtomicInteger tpexCalls = new AtomicInteger();
		MarketResolver resolver = resolverWithCounting(twseCalls, StockLookup.notFound(Market.TWSE),
				tpexCalls, StockLookup.notFound(Market.TPEX));

		assertThatThrownBy(() -> resolver.resolve("9999")).isInstanceOf(StockCodeNotFoundException.class);
		int callsAfterFirst = twseCalls.get() + tpexCalls.get();
		assertThatThrownBy(() -> resolver.resolve("9999"))
				.isInstanceOf(StockCodeNotFoundException.class)
				.hasMessage("查無此股票代號：9999（上市、上櫃都找不到）");

		assertThat(twseCalls.get() + tpexCalls.get()).isEqualTo(callsAfterFirst);
	}

	@Test
	void 無法確認的結果不記住_下次會重新查() {
		AtomicInteger twseCalls = new AtomicInteger();
		AtomicInteger tpexCalls = new AtomicInteger();
		MarketResolver resolver = resolverWithCounting(twseCalls, StockLookup.unavailable(Market.TWSE),
				tpexCalls, StockLookup.notFound(Market.TPEX));

		assertThatThrownBy(() -> resolver.resolve("2330")).isInstanceOf(MarketUnavailableException.class);
		assertThatThrownBy(() -> resolver.resolve("2330")).isInstanceOf(MarketUnavailableException.class);

		assertThat(twseCalls.get()).isEqualTo(2);
	}

	@Test
	void 不同代號各自快取() {
		AtomicInteger twseCalls = new AtomicInteger();
		AtomicInteger tpexCalls = new AtomicInteger();
		MarketResolver resolver = resolverWithCounting(twseCalls, found(Market.TWSE),
				tpexCalls, StockLookup.notFound(Market.TPEX));

		resolver.resolve("2330");
		resolver.resolve("2317");

		assertThat(twseCalls.get()).isEqualTo(2);
	}
}
