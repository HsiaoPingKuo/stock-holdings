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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.exception.MarketAmbiguousException;
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

	// ===== 兩邊都查到（F-03）：選最近交易日較新的一邊，日期相同就無法判斷 =====

	private static StockLookup foundOn(Market market, LocalDate tradeDate) {
		return StockLookup.found(market, "測試", tradeDate, new BigDecimal("10.00"));
	}

	private MarketResolver resolverBothFound(LocalDate twseDate, LocalDate tpexDate) {
		resolver = new MarketResolver(
				fakeClient(Market.TWSE, (code, day) -> foundOn(Market.TWSE, twseDate)),
				fakeClient(Market.TPEX, (code, day) -> foundOn(Market.TPEX, tpexDate)),
				clockAt(LocalDate.of(2026, 11, 24)), LOOKUP_TIMEOUT, CACHE_TTL);
		return resolver;
	}

	@Test
	void 兩邊都查到_證交所日期較舊_選上櫃() {
		MarketResolver resolver = resolverBothFound(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 24));

		StockLookup result = resolver.resolve("1234");

		assertThat(result.market()).isEqualTo(Market.TPEX);
		assertThat(result.tradeDate()).isEqualTo(LocalDate.of(2026, 11, 24));
	}

	@Test
	void 兩邊都查到_櫃買日期較舊_選上市() {
		MarketResolver resolver = resolverBothFound(LocalDate.of(2026, 11, 24), LocalDate.of(2026, 10, 30));

		StockLookup result = resolver.resolve("1234");

		assertThat(result.market()).isEqualTo(Market.TWSE);
		assertThat(result.tradeDate()).isEqualTo(LocalDate.of(2026, 11, 24));
	}

	@Test
	void 兩邊都查到_日期相同_無法判斷且不記住() {
		AtomicInteger twseCalls = new AtomicInteger();
		AtomicInteger tpexCalls = new AtomicInteger();
		LocalDate sameDay = LocalDate.of(2026, 11, 24);
		MarketResolver resolver = resolverWithCounting(twseCalls, foundOn(Market.TWSE, sameDay),
				tpexCalls, foundOn(Market.TPEX, sameDay));

		assertThatThrownBy(() -> resolver.resolve("1234"))
				.isInstanceOf(MarketAmbiguousException.class)
				.hasMessage("無法判斷 1234 是上市或上櫃，請聯絡管理者");
		// 「不儲存」：沒有記進 10 分鐘的快取，再查一次會重新問官方
		assertThatThrownBy(() -> resolver.resolve("1234")).isInstanceOf(MarketAmbiguousException.class);
		assertThat(twseCalls.get()).isEqualTo(2);
		assertThat(tpexCalls.get()).isEqualTo(2);
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

	// ===== 同一代號同時查詢只打一次官方（F-02） =====

	/**
	 * 每次「查最新資料」算一輪，而且要等 gate 打開才回答：用來讓多個請求確實「同時」在等官方。
	 * started 在第一輪開始時倒數，測試用它確認官方已經「收到請求、還沒回應」。
	 */
	private static StockLookupClient gatedClient(Market market, AtomicInteger rounds, CountDownLatch started,
			CountDownLatch gate, StockLookup answer) {
		return new StockLookupClient() {
			@Override
			public Market market() {
				return market;
			}

			@Override
			public StockLookup lookupMonth(String code, LocalDate dayInMonth) {
				return answer;
			}

			@Override
			public StockLookup lookupLatest(String code, LocalDate today) {
				rounds.incrementAndGet();
				started.countDown();
				try {
					// 最多等 5 秒，測試寫錯時不會整個卡住
					gate.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return answer;
			}
		};
	}

	/** 同時送出 count 個 resolve(code)。 */
	private static List<Future<StockLookup>> resolveConcurrently(ExecutorService callers, MarketResolver resolver,
			String code, int count) {
		List<Future<StockLookup>> results = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			results.add(callers.submit(() -> resolver.resolve(code)));
		}
		return results;
	}

	@Test
	void 同一代號8個請求同時查_官方只收到一輪請求_大家拿到同一個結果() throws Exception {
		AtomicInteger twseRounds = new AtomicInteger();
		AtomicInteger tpexRounds = new AtomicInteger();
		CountDownLatch twseStarted = new CountDownLatch(1);
		CountDownLatch gate = new CountDownLatch(1);
		resolver = new MarketResolver(
				gatedClient(Market.TWSE, twseRounds, twseStarted, gate, found(Market.TWSE)),
				gatedClient(Market.TPEX, tpexRounds, new CountDownLatch(1), gate, StockLookup.notFound(Market.TPEX)),
				clockAt(LocalDate.of(2026, 11, 24)), LOOKUP_TIMEOUT, CACHE_TTL);
		ExecutorService callers = Executors.newFixedThreadPool(8);
		try {
			List<Future<StockLookup>> results = resolveConcurrently(callers, resolver, "2330", 8);
			assertThat(twseStarted.await(2, TimeUnit.SECONDS)).isTrue();
			// 官方還沒回應：給其他 7 個請求一點時間進到 resolve，確定大家是「同時」在等
			Thread.sleep(300);
			gate.countDown();

			for (Future<StockLookup> result : results) {
				assertThat(result.get(5, TimeUnit.SECONDS).market()).isEqualTo(Market.TWSE);
			}
		} finally {
			callers.shutdownNow();
		}
		assertThat(twseRounds.get()).isEqualTo(1);
		assertThat(tpexRounds.get()).isEqualTo(1);
	}

	@Test
	void 某個代號的官方查詢卡住時_不同代號不受影響() throws Exception {
		CountDownLatch slowStarted = new CountDownLatch(1);
		CountDownLatch gate = new CountDownLatch(1);
		resolver = new MarketResolver(
				fakeClient(Market.TWSE, (code, day) -> {
					if (code.equals("2330")) {
						slowStarted.countDown();
						try {
							gate.await(5, TimeUnit.SECONDS);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
					}
					return found(Market.TWSE);
				}),
				fakeClient(Market.TPEX, (code, day) -> StockLookup.notFound(Market.TPEX)),
				clockAt(LocalDate.of(2026, 11, 24)), LOOKUP_TIMEOUT, CACHE_TTL);
		ExecutorService callers = Executors.newSingleThreadExecutor();
		try {
			Future<StockLookup> slow = callers.submit(() -> resolver.resolve("2330"));
			assertThat(slowStarted.await(2, TimeUnit.SECONDS)).isTrue();

			long start = System.nanoTime();
			StockLookup other = resolver.resolve("6488");

			assertThat(other.market()).isEqualTo(Market.TWSE);
			assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
			// 2330 還在等官方，沒有被 6488 的結果影響
			assertThat(slow.isDone()).isFalse();
			gate.countDown();
			assertThat(slow.get(5, TimeUnit.SECONDS).market()).isEqualTo(Market.TWSE);
		} finally {
			callers.shutdownNow();
		}
	}

	@Test
	void 共用的查詢失敗_等待的請求都收到無法確認_下一次會重新查官方() throws Exception {
		AtomicInteger twseRounds = new AtomicInteger();
		AtomicInteger tpexRounds = new AtomicInteger();
		CountDownLatch twseStarted = new CountDownLatch(1);
		CountDownLatch gate = new CountDownLatch(1);
		resolver = new MarketResolver(
				gatedClient(Market.TWSE, twseRounds, twseStarted, gate, StockLookup.unavailable(Market.TWSE)),
				gatedClient(Market.TPEX, tpexRounds, new CountDownLatch(1), gate, StockLookup.notFound(Market.TPEX)),
				clockAt(LocalDate.of(2026, 11, 24)), LOOKUP_TIMEOUT, CACHE_TTL);
		ExecutorService callers = Executors.newFixedThreadPool(3);
		try {
			List<Future<StockLookup>> results = resolveConcurrently(callers, resolver, "2330", 3);
			assertThat(twseStarted.await(2, TimeUnit.SECONDS)).isTrue();
			Thread.sleep(300);
			gate.countDown();

			for (Future<StockLookup> result : results) {
				assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
						.hasCauseInstanceOf(MarketUnavailableException.class);
			}
		} finally {
			callers.shutdownNow();
		}
		assertThat(twseRounds.get()).isEqualTo(1);

		// 失敗後「進行中」的紀錄已清掉：再查一次會重新問官方，而不是一直拿到同一個失敗結果
		assertThatThrownBy(() -> resolver.resolve("2330")).isInstanceOf(MarketUnavailableException.class);
		assertThat(twseRounds.get()).isEqualTo(2);
	}

	// ===== 時間預算與同時查詢上限（F-01） =====

	/** 等 latch，最多 5 秒（測試寫錯時不會整個卡住）。 */
	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void sleepQuietly(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Test
	void 名額已滿_等不到名額就快速回覆無法確認_即時提示和新增共用名額() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch gate = new CountDownLatch(1);
		AtomicInteger otherCalls = new AtomicInteger();
		// 同時查官方最多 1 個；resolve(code) 的預算 1 秒、至少要保留 0.5 秒
		resolver = new MarketResolver(
				fakeClient(Market.TWSE, (code, day) -> {
					if (code.equals("2330")) {
						started.countDown();
						awaitQuietly(gate);
					} else {
						otherCalls.incrementAndGet();
					}
					return found(Market.TWSE);
				}),
				fakeClient(Market.TPEX, (code, day) -> StockLookup.notFound(Market.TPEX)),
				clockAt(LocalDate.of(2026, 11, 24)), LOOKUP_TIMEOUT, CACHE_TTL,
				Duration.ofSeconds(1), 1, Duration.ofMillis(500));
		ExecutorService callers = Executors.newSingleThreadExecutor();
		try {
			// 新增持股那條路（帶截止時間）先占走唯一的名額
			Future<StockLookup> first = callers.submit(() -> resolver.resolve("2330", Deadline.after(Duration.ofSeconds(5))));
			assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

			// 即時提示那條路（resolve(code)，自己的預算 1 秒）：名額被占走，最多等 1 − 0.5 = 0.5 秒就放棄
			long start = System.nanoTime();
			assertThatThrownBy(() -> resolver.resolve("6488")).isInstanceOf(MarketUnavailableException.class);
			Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

			assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(400)).isLessThan(Duration.ofSeconds(1));
			// 沒拿到名額就不會打官方
			assertThat(otherCalls.get()).isZero();
			gate.countDown();
			assertThat(first.get(5, TimeUnit.SECONDS).market()).isEqualTo(Market.TWSE);
		} finally {
			callers.shutdownNow();
		}
	}

	@Test
	void 排隊後只剩多少時間_查官方就只等多少_不是固定的lookupTimeout() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		// lookupTimeout 故意設 8 秒；同時查官方最多 1 個；至少保留 0.2 秒
		resolver = new MarketResolver(
				fakeClient(Market.TWSE, (code, day) -> {
					if (code.equals("2330")) {
						started.countDown();
						sleepQuietly(1000); // 先來的請求占住名額 1 秒
					} else {
						sleepQuietly(5000); // 後來的請求遇到很慢的官方
					}
					return found(Market.TWSE);
				}),
				fakeClient(Market.TPEX, (code, day) -> StockLookup.notFound(Market.TPEX)),
				clockAt(LocalDate.of(2026, 11, 24)), Duration.ofSeconds(8), CACHE_TTL,
				Duration.ofSeconds(9), 1, Duration.ofMillis(200));
		ExecutorService callers = Executors.newSingleThreadExecutor();
		try {
			Future<StockLookup> first = callers.submit(() -> resolver.resolve("2330", Deadline.after(Duration.ofSeconds(5))));
			assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

			// 預算 2 秒：排隊約 1 秒後只剩約 1 秒，查官方只能等「剩下的時間 − 0.5 秒緩衝」，不是 8 秒
			long start = System.nanoTime();
			assertThatThrownBy(() -> resolver.resolve("6488", Deadline.after(Duration.ofSeconds(2))))
					.isInstanceOf(MarketUnavailableException.class);
			Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

			// 有排隊（等到先來的請求還名額），而且整體沒有超過 2 秒的預算
			assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(800)).isLessThan(Duration.ofSeconds(2));
			assertThat(first.get(5, TimeUnit.SECONDS).market()).isEqualTo(Market.TWSE);
		} finally {
			callers.shutdownNow();
		}
	}

	@Test
	void 名額等官方真的回應後才歸還_不是逾時就歸還() {
		CountDownLatch gate = new CountDownLatch(1);
		AtomicInteger otherCalls = new AtomicInteger();
		// lookupTimeout 只有 0.2 秒；同時查官方最多 1 個；不保留最少時間
		resolver = new MarketResolver(
				fakeClient(Market.TWSE, (code, day) -> {
					if (code.equals("2330")) {
						awaitQuietly(gate); // 官方一直沒回應，直到 gate 打開
					} else {
						otherCalls.incrementAndGet();
					}
					return found(Market.TWSE);
				}),
				fakeClient(Market.TPEX, (code, day) -> StockLookup.notFound(Market.TPEX)),
				clockAt(LocalDate.of(2026, 11, 24)), Duration.ofMillis(200), CACHE_TTL,
				Duration.ofSeconds(2), 1, Duration.ZERO);

		// 0.2 秒就放棄等待，但對官方的請求其實還在跑
		assertThatThrownBy(() -> resolver.resolve("2330")).isInstanceOf(MarketUnavailableException.class);
		// 名額還在那個請求手上：另一個代號拿不到名額，也不會打官方。
		// 給 1 秒（大於 0.5 秒的緩衝）：如果名額被提早歸還，這裡就會真的去打官方，otherCalls 會變成 1
		assertThatThrownBy(() -> resolver.resolve("6488", Deadline.after(Duration.ofSeconds(1))))
				.isInstanceOf(MarketUnavailableException.class);
		assertThat(otherCalls.get()).isZero();

		// 官方回應了 → 名額歸還 → 之後的查詢可以正常進行
		gate.countDown();
		assertThat(resolver.resolve("6488").market()).isEqualTo(Market.TWSE);
		assertThat(otherCalls.get()).isEqualTo(1);
	}

	@Test
	void 共用查詢時官方一直不回應_每個請求仍在自己的上限內結束() throws Exception {
		CountDownLatch never = new CountDownLatch(1);
		resolver = new MarketResolver(
				fakeClient(Market.TWSE, (code, day) -> {
					try {
						never.await(); // 模擬官方一直不回應
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					return StockLookup.unavailable(Market.TWSE);
				}),
				fakeClient(Market.TPEX, (code, day) -> StockLookup.notFound(Market.TPEX)),
				clockAt(LocalDate.of(2026, 11, 24)), Duration.ofMillis(200), CACHE_TTL);
		ExecutorService callers = Executors.newFixedThreadPool(3);
		try {
			long start = System.nanoTime();
			List<Future<StockLookup>> results = resolveConcurrently(callers, resolver, "9999", 3);

			for (Future<StockLookup> result : results) {
				assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
						.hasCauseInstanceOf(MarketUnavailableException.class);
			}
			assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
		} finally {
			callers.shutdownNow();
		}
	}

	// ===== 只查一個市場：新增持股時快取有這一列、但沒有收盤價（巧馨檢查時發現） =====

	@Test
	void 只查一個市場_只呼叫那個市場的client_回傳往前找到的收盤價() {
		AtomicInteger tpexCalls = new AtomicInteger();
		resolver = new MarketResolver(
				// 這個月最後一個交易日 11/23 沒成交，收盤價是 11/20 的
				fakeClient(Market.TWSE, (code, day) -> StockLookup.found(Market.TWSE, "矽統", LocalDate.of(2026, 11, 23),
						new BigDecimal("10.00"), LocalDate.of(2026, 11, 20))),
				countingClient(Market.TPEX, tpexCalls, StockLookup.notFound(Market.TPEX)),
				clockAt(LocalDate.of(2026, 11, 24)), LOOKUP_TIMEOUT, CACHE_TTL);

		StockLookup result = resolver.lookupLatestInMarket("2363", Market.TWSE, Deadline.after(Duration.ofSeconds(5)));

		assertThat(result.status()).isEqualTo(Status.FOUND);
		assertThat(result.closePrice()).isEqualByComparingTo("10.00");
		assertThat(result.closeDate()).isEqualTo(LocalDate.of(2026, 11, 20));
		assertThat(tpexCalls.get()).isZero();
	}

	@Test
	void 只查一個市場_被擋時回BLOCKED_不丟例外() {
		resolver = new MarketResolver(
				fakeClient(Market.TWSE, (code, day) -> StockLookup.notFound(Market.TWSE)),
				fakeClient(Market.TPEX, (code, day) -> StockLookup.blocked(Market.TPEX)),
				clockAt(LocalDate.of(2026, 11, 24)), LOOKUP_TIMEOUT, CACHE_TTL);

		StockLookup result = resolver.lookupLatestInMarket("6488", Market.TPEX, Deadline.after(Duration.ofSeconds(5)));

		assertThat(result.status()).isEqualTo(Status.BLOCKED);
	}

	@Test
	void 只查一個市場_官方一直不回應_在時間內回UNAVAILABLE_不丟例外() {
		CountDownLatch never = new CountDownLatch(1);
		resolver = new MarketResolver(
				fakeClient(Market.TWSE, (code, day) -> {
					awaitQuietly(never); // 模擬官方很久都不回應（awaitQuietly 最多等 5 秒）
					return found(Market.TWSE);
				}),
				fakeClient(Market.TPEX, (code, day) -> StockLookup.notFound(Market.TPEX)),
				clockAt(LocalDate.of(2026, 11, 24)), Duration.ofMillis(200), CACHE_TTL);

		long start = System.nanoTime();
		StockLookup result = resolver.lookupLatestInMarket("2363", Market.TWSE, Deadline.after(Duration.ofSeconds(5)));

		assertThat(result.status()).isEqualTo(Status.UNAVAILABLE);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
		never.countDown();
	}

	@Test
	void 只查一個市場_和新增共用名額_名額已滿時快速回UNAVAILABLE而且不打官方() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch gate = new CountDownLatch(1);
		AtomicInteger otherCalls = new AtomicInteger();
		// 同時查官方最多 1 個；至少要保留 0.5 秒
		resolver = new MarketResolver(
				fakeClient(Market.TWSE, (code, day) -> {
					if (code.equals("2330")) {
						started.countDown();
						awaitQuietly(gate);
					} else {
						otherCalls.incrementAndGet();
					}
					return found(Market.TWSE);
				}),
				fakeClient(Market.TPEX, (code, day) -> StockLookup.notFound(Market.TPEX)),
				clockAt(LocalDate.of(2026, 11, 24)), LOOKUP_TIMEOUT, CACHE_TTL,
				Duration.ofSeconds(1), 1, Duration.ofMillis(500));
		ExecutorService callers = Executors.newSingleThreadExecutor();
		try {
			// 另一個新增先占走唯一的名額
			Future<StockLookup> first = callers.submit(() -> resolver.resolve("2330", Deadline.after(Duration.ofSeconds(5))));
			assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

			long start = System.nanoTime();
			StockLookup result = resolver.lookupLatestInMarket("2363", Market.TWSE, Deadline.after(Duration.ofSeconds(1)));
			Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

			assertThat(result.status()).isEqualTo(Status.UNAVAILABLE);
			// 最多等 1 − 0.5 = 0.5 秒就放棄，沒有超過預算
			assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
			assertThat(otherCalls.get()).isZero();
			gate.countDown();
			assertThat(first.get(5, TimeUnit.SECONDS).market()).isEqualTo(Market.TWSE);
		} finally {
			callers.shutdownNow();
		}
	}
}
