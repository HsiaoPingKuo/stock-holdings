package io.hsiaopingkuo.stockholdings.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import io.hsiaopingkuo.stockholdings.config.QuoteProperties;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.exception.DuplicateCodeException;
import io.hsiaopingkuo.stockholdings.exception.MarketUnavailableException;
import io.hsiaopingkuo.stockholdings.quote.MarketResolver;
import io.hsiaopingkuo.stockholdings.quote.StockLookup;
import io.hsiaopingkuo.stockholdings.quote.StockLookupClient;
import io.hsiaopingkuo.stockholdings.repository.HoldingRepository;
import io.hsiaopingkuo.stockholdings.repository.QuoteRepository;

/**
 * F-01：官方很慢、很多人同時新增時，每個新增仍在 10 秒內有結果，清單也不會被拖慢。
 * 啟動整個程式（真的資料庫連線池、真的交易），只把官方換成「每次都要 3 秒才回應」的假 client。
 * lookup-timeout 用正式環境的 8 秒（測試設定檔是 2 秒，3 秒的假官方會全部逾時，測不到排隊的情況）。
 * 用獨立的記憶體資料庫，不和其他測試共用資料。
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:create-concurrency;DB_CLOSE_DELAY=-1",
		"quote.lookup-timeout=8s" })
class CreateHoldingConcurrencyTest {

	private static final Duration OFFICIAL_DELAY = Duration.ofSeconds(3);
	private static final Duration REQUIREMENT = Duration.ofSeconds(10);

	@TestConfiguration
	static class SlowOfficialConfig {

		// @Primary：程式裡原本那個 MarketResolver 還在，HoldingService 改用這個接假官方的
		@Bean
		@Primary
		MarketResolver slowMarketResolver(Clock clock, QuoteProperties properties) {
			return new MarketResolver(slowClient(Market.TWSE, true), slowClient(Market.TPEX, false), clock,
					properties.lookupTimeout(), properties.lookupCacheTtl(), properties.requestBudget(),
					properties.maxConcurrentLookups(), properties.lookupMinRemaining());
		}
	}

	/** 每次都要 3 秒才回應的假官方；found = true 回查到（上市），false 回查不到。 */
	private static StockLookupClient slowClient(Market market, boolean found) {
		return new StockLookupClient() {
			@Override
			public Market market() {
				return market;
			}

			@Override
			public StockLookup lookupMonth(String code, LocalDate dayInMonth) {
				throw new UnsupportedOperationException("這個假 client 只實作 lookupLatest");
			}

			@Override
			public StockLookup lookupLatest(String code, LocalDate today) {
				try {
					// Java 17 沒有 Thread.sleep(Duration)（19 才有），用毫秒
					Thread.sleep(OFFICIAL_DELAY.toMillis());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return StockLookup.unavailable(market);
				}
				return found
						? StockLookup.found(market, "測試" + code, today, new BigDecimal("10.00"))
						: StockLookup.notFound(market);
			}
		};
	}

	@Autowired
	private HoldingService holdingService;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private QuoteRepository quoteRepository;

	@BeforeEach
	@AfterEach
	void 清空資料() {
		holdingRepository.deleteAll();
		quoteRepository.deleteAll();
	}

	private enum Result {
		OK, UNAVAILABLE, DUPLICATE
	}

	private record Outcome(Result result, Duration elapsed) {
	}

	/** 新增一筆並記下結果和花了多久；預期以外的例外會讓 future.get() 失敗，測試就不會通過。 */
	private Callable<Outcome> create(String code, CountDownLatch startGate) {
		return () -> {
			startGate.await();
			long start = System.nanoTime();
			Result result;
			try {
				holdingService.create(code, 1, new BigDecimal("100.00"));
				result = Result.OK;
			} catch (MarketUnavailableException e) {
				result = Result.UNAVAILABLE;
			} catch (DuplicateCodeException e) {
				result = Result.DUPLICATE;
			}
			return new Outcome(result, Duration.ofNanos(System.nanoTime() - start));
		};
	}

	private static long count(List<Outcome> outcomes, Result result) {
		return outcomes.stream().filter(o -> o.result() == result).count();
	}

	@Test
	void 官方每次延遲3秒_同時20個新增_每個都在10秒內有結果_清單查詢不被拖慢() throws Exception {
		int requests = 20;
		ExecutorService pool = Executors.newFixedThreadPool(requests + 1);
		CountDownLatch startGate = new CountDownLatch(1);
		AtomicBoolean creating = new AtomicBoolean(true);
		try {
			List<Future<Outcome>> futures = new ArrayList<>();
			for (int i = 0; i < requests; i++) {
				// 20 個不同代號，每個都要查官方（F-02 的共用查詢不會幫忙）
				futures.add(pool.submit(create(String.valueOf(1101 + i), startGate)));
			}
			// 新增進行中，另一個使用者每 0.1 秒打開一次清單，記下最慢的一次
			Future<Duration> slowestList = pool.submit(() -> {
				startGate.await();
				Duration slowest = Duration.ZERO;
				while (creating.get()) {
					long start = System.nanoTime();
					holdingService.findAllForList();
					Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
					slowest = elapsed.compareTo(slowest) > 0 ? elapsed : slowest;
					Thread.sleep(100);
				}
				return slowest;
			});

			startGate.countDown();
			List<Outcome> outcomes = new ArrayList<>();
			for (Future<Outcome> future : futures) {
				outcomes.add(future.get(15, TimeUnit.SECONDS));
			}
			creating.set(false);

			assertThat(outcomes).allSatisfy(o -> assertThat(o.elapsed()).isLessThan(REQUIREMENT));
			// 每個都是「新增成功」或「暫時無法確認」，沒有其他錯誤（其他例外會在 future.get 失敗）
			assertThat(count(outcomes, Result.OK) + count(outcomes, Result.UNAVAILABLE)).isEqualTo(requests);
			// 同時查官方的上限是 4 個：至少第一批 4 個一定查得到、存得進去
			assertThat(count(outcomes, Result.OK)).isGreaterThanOrEqualTo(4);
			assertThat(holdingRepository.count()).isEqualTo(count(outcomes, Result.OK));
			// 等官方的期間沒有占著資料庫連線，清單查詢不會被拖慢
			assertThat(slowestList.get(5, TimeUnit.SECONDS)).isLessThan(Duration.ofSeconds(1));
		} finally {
			creating.set(false);
			pool.shutdownNow();
		}
	}

	@Test
	void 同時新增同一代號_只會存一筆_其他都是重複代號() throws Exception {
		int requests = 10;
		ExecutorService pool = Executors.newFixedThreadPool(requests);
		CountDownLatch startGate = new CountDownLatch(1);
		try {
			List<Future<Outcome>> futures = new ArrayList<>();
			for (int i = 0; i < requests; i++) {
				futures.add(pool.submit(create("2330", startGate)));
			}
			startGate.countDown();
			List<Outcome> outcomes = new ArrayList<>();
			for (Future<Outcome> future : futures) {
				outcomes.add(future.get(15, TimeUnit.SECONDS));
			}

			// 大家都通過了第一道查重（那時還沒有人存進去），最後由資料庫的 UNIQUE 約束擋下，轉成「重複代號」
			assertThat(count(outcomes, Result.OK)).isEqualTo(1);
			assertThat(count(outcomes, Result.DUPLICATE)).isEqualTo(requests - 1);
			assertThat(holdingRepository.findAll()).singleElement()
					.satisfies(h -> assertThat(h.getCode()).isEqualTo("2330"));
			assertThat(outcomes).allSatisfy(o -> assertThat(o.elapsed()).isLessThan(REQUIREMENT));
		} finally {
			pool.shutdownNow();
		}
	}
}
