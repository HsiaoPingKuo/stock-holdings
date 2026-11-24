package io.hsiaopingkuo.stockholdings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.hsiaopingkuo.stockholdings.calc.TradeSide;
import io.hsiaopingkuo.stockholdings.config.IdempotencyProperties;
import io.hsiaopingkuo.stockholdings.config.QuoteProperties;
import io.hsiaopingkuo.stockholdings.dto.IdempotencyRequest;
import io.hsiaopingkuo.stockholdings.dto.StoredResponse;
import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.entity.IdempotencyRecord;
import io.hsiaopingkuo.stockholdings.entity.IdempotencyStatus;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.entity.Trade;
import io.hsiaopingkuo.stockholdings.exception.IdempotencyKeyReusedException;
import io.hsiaopingkuo.stockholdings.exception.RequestInProgressException;
import io.hsiaopingkuo.stockholdings.exception.TradeOversellException;
import io.hsiaopingkuo.stockholdings.quote.Deadline;
import io.hsiaopingkuo.stockholdings.quote.MarketResolver;
import io.hsiaopingkuo.stockholdings.quote.StockLookup;
import io.hsiaopingkuo.stockholdings.repository.HoldingRepository;
import io.hsiaopingkuo.stockholdings.repository.IdempotencyRecordRepository;
import io.hsiaopingkuo.stockholdings.repository.TradeRepository;

/** 冪等鍵（第 6 題第 5b 階段），搭配真的資料庫和真的 TradeService。 */
@DataJpaTest
@Import({ IdempotencyService.class, TradeService.class, StockIdentityResolver.class, QuoteService.class,
		DatabaseDividendEventProvider.class, IdempotencyServiceTest.FixedClockConfig.class })
// 冪等鍵借用 TradeService 自己開的交易，測試本身不能包交易；資料由「清空資料」負責清掉
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IdempotencyServiceTest {

	// 台北 2026-11-24（星期二）14:00
	private static final Instant FIXED = Instant.parse("2026-11-24T06:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 14, 0);
	private static final LocalDate DAY = LocalDate.of(2026, 11, 23);
	private static final String ADD_API = "POST /api/v2/trades";

	@TestConfiguration
	@EnableConfigurationProperties({ QuoteProperties.class, IdempotencyProperties.class })
	static class FixedClockConfig {
		@Bean
		Clock clock() {
			return Clock.fixed(FIXED, ZoneId.of("Asia/Taipei"));
		}
	}

	@Autowired
	private IdempotencyService idempotencyService;

	@Autowired
	private TradeService tradeService;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private IdempotencyRecordRepository recordRepository;

	// 不連網：全新代號一律判斷為上市
	@MockitoBean
	private MarketResolver marketResolver;

	@BeforeEach
	void 預設任何代號都判斷為上市() {
		given(marketResolver.resolve(anyString(), any(Deadline.class))).willAnswer(invocation -> StockLookup.found(
				Market.TWSE, "測試股", DAY, new BigDecimal("10.00")));
	}

	@BeforeEach
	@AfterEach
	void 清空資料() {
		recordRepository.deleteAll();
		holdingRepository.deleteAll();
	}

	@Test
	void 同一個鍵同樣內容送兩次_只寫入一筆_第二次拿到一樣的結果() {
		// 用全新代號：同時驗證「查既有持股」那段短交易回滾後，鍵沒有先 commit
		IdempotencyRequest request = addRequest("key-1", "2330", TradeSide.BUY, 1000);

		StoredResponse first = add(request, "2330", TradeSide.BUY, 1000);
		StoredResponse second = add(request, "2330", TradeSide.BUY, 1000);

		assertThat(second).isEqualTo(first);
		assertThat(first.status()).isEqualTo(201);
		assertThat(tradeRepository.count()).isEqualTo(1);
		// 第二次直接回傳上次的結果，沒有再查官方
		verify(marketResolver, times(1)).resolve(anyString(), any(Deadline.class));
		IdempotencyRecord record = recordRepository.findByIdempotencyKey("key-1").orElseThrow();
		assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
		assertThat(record.getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	void 同一個鍵不同內容_回鍵被重複使用且不寫入() {
		saveHoldingWithBuy("2330", 1000);
		add(addRequest("key-1", "2330", TradeSide.BUY, 500), "2330", TradeSide.BUY, 500);

		assertThatThrownBy(() -> add(addRequest("key-1", "2330", TradeSide.BUY, 600), "2330", TradeSide.BUY, 600))
				.isInstanceOf(IdempotencyKeyReusedException.class);

		assertThat(tradeRepository.count()).isEqualTo(2);
	}

	@Test
	void 第一次超賣失敗_鍵不保留_修正後同一個鍵可以成功() {
		saveHoldingWithBuy("2330", 1000);

		assertThatThrownBy(() -> add(addRequest("key-1", "2330", TradeSide.SELL, 2000), "2330", TradeSide.SELL, 2000))
				.isInstanceOf(TradeOversellException.class);
		assertThat(recordRepository.count()).isZero();

		StoredResponse fixed = add(addRequest("key-1", "2330", TradeSide.SELL, 500), "2330", TradeSide.SELL, 500);

		assertThat(fixed.status()).isEqualTo(201);
		assertThat(tradeRepository.count()).isEqualTo(2);
	}

	@Test
	void 第一次還在處理中_第二次回處理中() throws Exception {
		saveHoldingWithBuy("2330", 1000);
		IdempotencyRequest request = addRequest("key-1", "2330", TradeSide.BUY, 500);
		// 第一個請求寫好鍵（還沒 commit）時通知；收到放行才繼續寫交易
		CountDownLatch keyWritten = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<StoredResponse> first = executor.submit(() -> idempotencyService.execute(request, this::created,
					hook -> tradeService.addTrade("2330", TradeSide.BUY, DAY, 500, new BigDecimal("100"),
							pauseAfterKey(hook, keyWritten, release))));
			assertThat(keyWritten.await(10, TimeUnit.SECONDS)).isTrue();

			assertThatThrownBy(() -> add(request, "2330", TradeSide.BUY, 500))
					.isInstanceOf(RequestInProgressException.class);

			release.countDown();
			assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo(201);
		} finally {
			release.countDown();
			executor.shutdownNow();
		}
		// 只有第一個請求寫入
		assertThat(tradeRepository.count()).isEqualTo(2);
	}

	@Test
	void 超過24小時的紀錄會被清掉() {
		recordRepository.save(completedRecord("old", NOW.minusHours(25)));
		recordRepository.save(completedRecord("recent", NOW.minusHours(23)));

		int deleted = idempotencyService.purgeExpired();

		assertThat(deleted).isEqualTo(1);
		assertThat(recordRepository.findAll()).extracting(IdempotencyRecord::getIdempotencyKey)
				.containsExactly("recent");
	}

	// ===== 測試用的小工具 =====

	private StoredResponse add(IdempotencyRequest request, String code, TradeSide side, long shares) {
		return idempotencyService.execute(request, this::created,
				hook -> tradeService.addTrade(code, side, DAY, shares, new BigDecimal("100"), hook));
	}

	// 第 6 階段由 controller 序列化成 JSON；這裡只要能分辨是哪一筆交易
	private StoredResponse created(Trade trade) {
		return new StoredResponse(201, "{\"id\":" + trade.getId() + "}");
	}

	private static IdempotencyRequest addRequest(String key, String code, TradeSide side, long shares) {
		return new IdempotencyRequest(key, ADD_API,
				IdempotencyRequest.sha256(String.join(",", code, side.name(), DAY.toString(), String.valueOf(shares))));
	}

	/** 寫好冪等鍵之後先停下來，等測試放行：用來製造「第一個請求還沒 commit」的狀態，不靠 sleep 碰運氣。 */
	private static TradeWriteHook pauseAfterKey(TradeWriteHook hook, CountDownLatch keyWritten,
			CountDownLatch release) {
		return new TradeWriteHook() {
			@Override
			public void beforeLock() {
				hook.beforeLock();
				keyWritten.countDown();
				try {
					if (!release.await(30, TimeUnit.SECONDS)) {
						throw new IllegalStateException("等不到測試放行");
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(e);
				}
			}

			@Override
			public void afterWrite(Trade trade) {
				hook.afterWrite(trade);
			}
		};
	}

	private void saveHoldingWithBuy(String code, long shares) {
		Holding holding = holdingRepository.saveAndFlush(new Holding(code, "測試股", Market.TWSE, NOW));
		tradeRepository.saveAndFlush(new Trade(holding.getId(), TradeSide.BUY, LocalDate.of(2026, 11, 18), shares,
				new BigDecimal("100"), false, NOW));
	}

	private static IdempotencyRecord completedRecord(String key, LocalDateTime createdAt) {
		IdempotencyRecord record = IdempotencyRecord.inProgress(key, ADD_API, IdempotencyRequest.sha256(key),
				createdAt);
		record.complete(201, "{}");
		return record;
	}
}
