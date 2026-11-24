package io.hsiaopingkuo.stockholdings.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.entity.Quote;
import io.hsiaopingkuo.stockholdings.quote.DailyQuote;
import io.hsiaopingkuo.stockholdings.repository.QuoteRepository;

/**
 * G-01：兩個交易同時寫同一檔行情，新資料不能被舊資料蓋掉。用真的 H2 和真的交易。
 * 關掉 @DataJpaTest 預設包住每個測試的交易：兩條執行緒要各自開交易、各自提交，才看得到鎖的效果。
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QuoteServiceConcurrencyTest {

	private static final String CODE = "2330";
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T06:00:00Z"), ZoneId.of("Asia/Taipei"));

	@Autowired
	private QuoteRepository quoteRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@AfterEach
	void cleanUp() {
		// 沒有測試交易幫忙回滾，自己清掉
		quoteRepository.deleteAll();
	}

	private static DailyQuote day(LocalDate date, String close) {
		return DailyQuote.ofDay(CODE, "台積電", Market.TWSE, date, new BigDecimal(close));
	}

	@Test
	void A讀到1123後暫停_B寫入1125_A再寫入1124_最後仍是1125與120() throws Exception {
		QuoteService plain = new QuoteService(quoteRepository, CLOCK, transactionManager);
		plain.upsert(List.of(day(LocalDate.of(2026, 11, 23), "100.00")));

		CountDownLatch aHasRead = new CountDownLatch(1);
		CountDownLatch bFinished = new CountDownLatch(1);
		// A 用的 repository：讀完既有的列後，通知 B 開始，再等 B 寫完（最多 0.5 秒）才繼續。
		// 沒有鎖時 B 會在這段時間寫完提交，A 接著用舊的比較結果蓋掉它（報告重現的情況）；
		// 有鎖時 B 卡在鎖上寫不完，A 等滿 0.5 秒後先提交，B 才拿到鎖、讀到 A 寫的 11/24 再比較。
		// 0.5 秒只是 A 等待的上限，不決定結果：不論 B 有沒有在這段時間寫完，最後都必須是 11/25。
		QuoteRepository pausingRepository = (QuoteRepository) Proxy.newProxyInstance(
				QuoteRepository.class.getClassLoader(), new Class<?>[] { QuoteRepository.class },
				(proxy, method, args) -> {
					Object result;
					try {
						result = method.invoke(quoteRepository, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
					if (method.getName().equals("findAllByCodeForUpdate")) {
						aHasRead.countDown();
						bFinished.await(500, TimeUnit.MILLISECONDS);
					}
					return result;
				});
		QuoteService pausingA = new QuoteService(pausingRepository, CLOCK, transactionManager);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Integer> a = executor.submit(() -> pausingA.upsert(List.of(day(LocalDate.of(2026, 11, 24), "110.00"))));
			assertThat(aHasRead.await(5, TimeUnit.SECONDS)).isTrue();
			Future<Integer> b = executor.submit(() -> {
				try {
					return plain.upsert(List.of(day(LocalDate.of(2026, 11, 25), "120.00")));
				} finally {
					bFinished.countDown();
				}
			});
			// get() 會把執行緒裡的例外（例如等不到鎖）丟出來，測試就會失敗
			a.get(10, TimeUnit.SECONDS);
			b.get(10, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}

		Quote quote = quoteRepository.findById(CODE).orElseThrow();
		assertThat(quote.getLatestTradeDate()).isEqualTo(LocalDate.of(2026, 11, 25));
		assertThat(quote.getCloseDate()).isEqualTo(LocalDate.of(2026, 11, 25));
		assertThat(quote.getClosePrice()).isEqualByComparingTo("120.00");
	}
}
