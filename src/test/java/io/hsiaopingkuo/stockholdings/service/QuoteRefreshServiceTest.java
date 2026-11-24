package io.hsiaopingkuo.stockholdings.service;

import static io.hsiaopingkuo.stockholdings.service.RefreshTestSupport.SETTINGS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.entity.Quote;
import io.hsiaopingkuo.stockholdings.quote.BatchQuoteResult;
import io.hsiaopingkuo.stockholdings.quote.DailyQuote;
import io.hsiaopingkuo.stockholdings.quote.MarketResolver;
import io.hsiaopingkuo.stockholdings.quote.StockLookup;
import io.hsiaopingkuo.stockholdings.repository.HoldingRepository;
import io.hsiaopingkuo.stockholdings.repository.QuoteRepository;
import io.hsiaopingkuo.stockholdings.service.QuoteRefreshService.Mode;
import io.hsiaopingkuo.stockholdings.service.QuoteRefreshService.Trigger;
import io.hsiaopingkuo.stockholdings.service.RefreshTestSupport.FakeBatchClient;
import io.hsiaopingkuo.stockholdings.service.RefreshTestSupport.FakeStockClient;
import io.hsiaopingkuo.stockholdings.service.RefreshTestSupport.MutableClock;
import io.hsiaopingkuo.stockholdings.service.RefreshTestSupport.RecordingPause;

/**
 * 單一市場一輪的更新流程。官方 client 全部是假的（不連網），資料庫用真的（H2 + Flyway），
 * 等待用 RecordingPause（不真的等），時間用可以撥的時鐘。
 */
@DataJpaTest
@Import({ QuoteService.class, QuoteRefreshServiceTest.ClockConfig.class })
class QuoteRefreshServiceTest {

	/** 2026-11-24 是週二。 */
	private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);
	private static final LocalDate YESTERDAY = LocalDate.of(2026, 11, 23);

	@TestConfiguration
	static class ClockConfig {
		// 只給 QuoteService 寫 updated_at 用；排程判斷用的是下面每個測試自己的 MutableClock
		@Bean
		Clock clock() {
			return Clock.fixed(Instant.parse("2026-11-24T06:00:00Z"), RefreshTestSupport.TAIPEI);
		}
	}

	@Autowired
	private QuoteService quoteService;

	@Autowired
	private QuoteRepository quoteRepository;

	@Autowired
	private HoldingRepository holdingRepository;

	private MutableClock clock;
	private RecordingPause pause;
	private FakeBatchClient twseBatch;
	private FakeBatchClient tpexBatch;
	private FakeStockClient twseStock;
	private FakeStockClient tpexStock;
	private MarketResolver marketResolver;
	private QuoteRefreshService service;

	@BeforeEach
	void setUp() {
		// 預設：台北 14:00；批次都「還沒有資料」、逐檔都查不了，各測試再依需要改
		clock = new MutableClock(LocalDateTime.of(2026, 11, 24, 14, 0));
		pause = new RecordingPause();
		twseBatch = new FakeBatchClient(Market.TWSE, date -> BatchQuoteResult.noData());
		tpexBatch = new FakeBatchClient(Market.TPEX, date -> BatchQuoteResult.noData());
		twseStock = new FakeStockClient(Market.TWSE, code -> StockLookup.unavailable(Market.TWSE));
		tpexStock = new FakeStockClient(Market.TPEX, code -> StockLookup.unavailable(Market.TPEX));
		marketResolver = mock(MarketResolver.class);
		service = newService(clock);
	}

	private QuoteRefreshService newService(Clock serviceClock) {
		HoldingBackfillService backfill = new HoldingBackfillService(holdingRepository, quoteService, marketResolver,
				pause, new Random(1), RefreshTestSupport.propertiesWith(SETTINGS), serviceClock);
		return new QuoteRefreshService(Map.of(Market.TWSE, twseBatch, Market.TPEX, tpexBatch),
				Map.of(Market.TWSE, twseStock, Market.TPEX, tpexStock), quoteService, quoteRepository,
				holdingRepository, backfill, pause, new Random(1), SETTINGS, serviceClock);
	}

	private static BatchQuoteResult batchOf(LocalDate date, Market market, String... codeAndClose) {
		List<DailyQuote> quotes = new ArrayList<>();
		for (int i = 0; i < codeAndClose.length; i += 2) {
			quotes.add(DailyQuote.ofDay(codeAndClose[i], "名稱" + codeAndClose[i], market, date,
					new BigDecimal(codeAndClose[i + 1])));
		}
		return BatchQuoteResult.ok(date, quotes);
	}

	private void hold(String code, Market market) {
		holdingRepository.saveAndFlush(new Holding(code, "名稱" + code, market, 1, new BigDecimal("100.00"),
				LocalDateTime.of(2026, 11, 20, 10, 0)));
	}

	private void cache(String code, Market market, LocalDate date) {
		quoteService.upsert(List.of(DailyQuote.ofDay(code, "名稱" + code, market, date, new BigDecimal("100.00"))));
	}

	// ===== 批次 =====

	@Test
	void 批次拿到今天的資料_寫入快取_該市場今天完成_之後的排程輪不再發請求() {
		twseBatch.answer = date -> batchOf(date, Market.TWSE, "2330", "2050.00", "2317", "200.00");

		var first = service.runRound(Market.TWSE, Trigger.SCHEDULED);
		clock.advance(Duration.ofMinutes(1));
		var second = service.runRound(Market.TWSE, Trigger.SCHEDULED);

		assertThat(first.mode()).isEqualTo(Mode.BATCH);
		assertThat(first.updated()).isEqualTo(2);
		assertThat(first.dataDate()).isEqualTo(TODAY);
		assertThat(quoteRepository.findById("2330").orElseThrow().getClosePrice()).isEqualByComparingTo("2050.00");
		assertThat(service.state(Market.TWSE).isDoneFor(TODAY)).isTrue();
		assertThat(second.mode()).isEqualTo(Mode.SKIPPED);
		assertThat(twseBatch.asked).containsExactly(TODAY);
	}

	@Test
	void 批次stat不是OK_視為還沒有資料_不退回逐檔_不報錯_也不算完成() {
		hold("2330", Market.TWSE);

		var result = service.runRound(Market.TWSE, Trigger.SCHEDULED);

		assertThat(result.mode()).isEqualTo(Mode.NO_DATA);
		assertThat(twseStock.asked).isEmpty();
		assertThat(service.state(Market.TWSE).isDoneFor(TODAY)).isFalse();
		assertThat(service.state(Market.TWSE).usedPerStock()).isFalse();
	}

	// ===== 逐檔備援 =====

	@Test
	void 批次失敗404_改走逐檔_只查該市場有人持有且快取還沒有今天資料的代號() {
		hold("2330", Market.TWSE);
		hold("2303", Market.TWSE);
		hold("2317", Market.TWSE);
		hold("6488", Market.TPEX);
		// 2317 的快取已經是今天的，不用再查
		cache("2317", Market.TWSE, TODAY);
		twseBatch.answer = date -> BatchQuoteResult.failed();
		twseStock.answer = code -> StockLookup.found(Market.TWSE, "名稱" + code, TODAY, new BigDecimal("50.00"));

		var result = service.runRound(Market.TWSE, Trigger.SCHEDULED);

		assertThat(result.mode()).isEqualTo(Mode.PER_STOCK);
		// 依代號排序；上櫃的 6488、已經是今天資料的 2317 都不查
		assertThat(twseStock.asked).containsExactly("2303", "2330");
		assertThat(tpexStock.asked).isEmpty();
		assertThat(quoteRepository.findById("2303").orElseThrow().getLatestTradeDate()).isEqualTo(TODAY);
		assertThat(service.state(Market.TWSE).usedPerStock()).isTrue();
		// 持有的代號都拿到今天的資料 → 今天完成
		assertThat(service.state(Market.TWSE).isDoneFor(TODAY)).isTrue();
	}

	@Test
	void 逐檔一次只送一個請求_請求之間隨機等待3點2到5點8秒() {
		hold("2330", Market.TWSE);
		hold("2303", Market.TWSE);
		hold("2317", Market.TWSE);
		twseBatch.answer = date -> BatchQuoteResult.failed();
		twseStock.answer = code -> StockLookup.found(Market.TWSE, "名稱" + code, TODAY, new BigDecimal("50.00"));

		service.runRound(Market.TWSE, Trigger.SCHEDULED);

		// 3 個請求之間等 2 次（第一個請求前不用等）
		assertThat(pause.waits).hasSize(2);
		assertThat(pause.waits).allSatisfy(wait -> {
			assertThat(wait).isGreaterThanOrEqualTo(Duration.ofMillis(3200));
			assertThat(wait).isLessThan(Duration.ofMillis(5800));
		});
	}

	@Test
	void 逐檔查到今天無成交_最近交易日是今天就算完成_不會一直重查() {
		hold("1234", Market.TWSE);
		cache("1234", Market.TWSE, YESTERDAY);
		twseBatch.answer = date -> BatchQuoteResult.failed();
		// 今天沒成交：收盤價沿用 11/20，但最近交易日是今天
		twseStock.answer = code -> StockLookup.found(Market.TWSE, "測試股", TODAY, new BigDecimal("10.00"),
				LocalDate.of(2026, 11, 20));

		service.runRound(Market.TWSE, Trigger.SCHEDULED);

		Quote quote = quoteRepository.findById("1234").orElseThrow();
		assertThat(quote.getLatestTradeDate()).isEqualTo(TODAY);
		// 快取裡 11/23 的收盤價比逐檔查到的 11/20 新，保留 11/23
		assertThat(quote.getCloseDate()).isEqualTo(YESTERDAY);
		assertThat(service.state(Market.TWSE).isDoneFor(TODAY)).isTrue();
	}

	@Test
	void 逐檔還沒拿到今天的資料_不算完成_下一輪繼續查() {
		hold("2330", Market.TWSE);
		twseBatch.answer = date -> BatchQuoteResult.failed();
		// 官方還沒公布：逐檔最後一列還是昨天
		twseStock.answer = code -> StockLookup.found(Market.TWSE, "台積電", YESTERDAY, new BigDecimal("2030.00"));

		service.runRound(Market.TWSE, Trigger.SCHEDULED);

		assertThat(service.state(Market.TWSE).isDoneFor(TODAY)).isFalse();
	}

	@Test
	void 沒有人持有這個市場的股票_批次失敗時不算完成_之後新增的持股才會被更新() {
		twseBatch.answer = date -> BatchQuoteResult.failed();

		var result = service.runRound(Market.TWSE, Trigger.SCHEDULED);

		assertThat(result.mode()).isEqualTo(Mode.PER_STOCK);
		assertThat(service.state(Market.TWSE).isDoneFor(TODAY)).isFalse();
	}

	// ===== 被擋與冷卻 =====

	@Test
	void 批次被擋_進入冷卻_冷卻期間不發請求_冷卻結束後恢復() {
		twseBatch.answer = date -> BatchQuoteResult.blocked();

		var blocked = service.runRound(Market.TWSE, Trigger.SCHEDULED);
		clock.advance(Duration.ofMinutes(29));
		var duringCooldown = service.runRound(Market.TWSE, Trigger.SCHEDULED);
		clock.advance(Duration.ofMinutes(2));
		service.runRound(Market.TWSE, Trigger.SCHEDULED);

		assertThat(blocked.mode()).isEqualTo(Mode.BLOCKED);
		assertThat(duringCooldown.mode()).isEqualTo(Mode.SKIPPED);
		// 第一輪 1 次、冷卻中 0 次、冷卻結束後 1 次
		assertThat(twseBatch.asked).hasSize(2);
	}

	@Test
	void 批次回HTML被擋_只冷卻_不退回逐檔() {
		hold("2330", Market.TWSE);
		twseBatch.answer = date -> BatchQuoteResult.blocked();

		service.runRound(Market.TWSE, Trigger.SCHEDULED);

		assertThat(twseStock.asked).isEmpty();
		assertThat(service.state(Market.TWSE).isCoolingDown(clock.instant())).isTrue();
	}

	@Test
	void 逐檔途中被擋_剩下的代號不查_進入冷卻() {
		hold("2303", Market.TWSE);
		hold("2317", Market.TWSE);
		hold("2330", Market.TWSE);
		twseBatch.answer = date -> BatchQuoteResult.failed();
		twseStock.answer = code -> code.equals("2317") ? StockLookup.blocked(Market.TWSE)
				: StockLookup.found(Market.TWSE, "名稱", TODAY, new BigDecimal("50.00"));

		var result = service.runRound(Market.TWSE, Trigger.SCHEDULED);

		assertThat(result.mode()).isEqualTo(Mode.BLOCKED);
		assertThat(twseStock.asked).containsExactly("2303", "2317");
		assertThat(service.state(Market.TWSE).isCoolingDown(clock.instant())).isTrue();
	}

	// ===== 上市、上櫃互不影響 =====

	@Test
	void 證交所被擋冷卻時_櫃買照常查詢() {
		twseBatch.answer = date -> BatchQuoteResult.blocked();
		tpexBatch.answer = date -> batchOf(date, Market.TPEX, "6488", "460.50");

		service.runRound(Market.TWSE, Trigger.SCHEDULED);
		var tpex = service.runRound(Market.TPEX, Trigger.SCHEDULED);

		assertThat(service.state(Market.TWSE).isCoolingDown(clock.instant())).isTrue();
		assertThat(service.state(Market.TPEX).isCoolingDown(clock.instant())).isFalse();
		assertThat(tpex.mode()).isEqualTo(Mode.BATCH);
		assertThat(tpexBatch.asked).containsExactly(TODAY);
	}

	@Test
	void 上市已拿到今天資料而停止_上櫃還沒有資料_仍繼續查() {
		twseBatch.answer = date -> batchOf(date, Market.TWSE, "2330", "2050.00");

		service.runRound(Market.TWSE, Trigger.SCHEDULED);
		service.runRound(Market.TPEX, Trigger.SCHEDULED);
		clock.advance(Duration.ofMinutes(1));
		service.runRound(Market.TWSE, Trigger.SCHEDULED);
		service.runRound(Market.TPEX, Trigger.SCHEDULED);

		assertThat(service.state(Market.TWSE).isDoneFor(TODAY)).isTrue();
		assertThat(service.state(Market.TPEX).isDoneFor(TODAY)).isFalse();
		assertThat(twseBatch.asked).hasSize(1);
		assertThat(tpexBatch.asked).hasSize(2);
	}

	// ===== 伺服器啟動時 =====

	@Test
	void 啟動_今天還沒有資料_往前一天找到最近的交易日() {
		tpexBatch.answer = date -> date.equals(YESTERDAY) ? batchOf(date, Market.TPEX, "6488", "460.50")
				: BatchQuoteResult.noData();

		var result = service.runRound(Market.TPEX, Trigger.STARTUP);

		assertThat(tpexBatch.asked).containsExactly(TODAY, YESTERDAY);
		assertThat(result.mode()).isEqualTo(Mode.BATCH);
		assertThat(result.dataDate()).isEqualTo(YESTERDAY);
		assertThat(quoteRepository.findById("6488").orElseThrow().getCloseDate()).isEqualTo(YESTERDAY);
		// 拿到的是昨天的資料：今天還沒完成，排程 13:40 後要繼續查
		assertThat(service.state(Market.TPEX).isDoneFor(TODAY)).isFalse();
		// 往前找的請求之間也要等
		assertThat(pause.waits).hasSize(1);
	}

	@Test
	void 啟動_最多往前找10天() {
		var result = service.runRound(Market.TWSE, Trigger.STARTUP);

		assertThat(result.mode()).isEqualTo(Mode.NO_DATA);
		// 今天 + 往前 10 天
		assertThat(twseBatch.asked).hasSize(11);
		assertThat(twseBatch.asked.get(10)).isEqualTo(TODAY.minusDays(10));
	}

	@Test
	void 啟動_今天已完成也照常查詢() {
		// 休市日或已完成只影響排程輪；啟動時一定查一次
		service.state(Market.TWSE).markDone(TODAY);
		twseBatch.answer = date -> batchOf(date, Market.TWSE, "2330", "2050.00");

		var result = service.runRound(Market.TWSE, Trigger.STARTUP);

		assertThat(result.mode()).isEqualTo(Mode.BATCH);
	}

	@Test
	void 官方連不上或client丟例外_這一輪不往外丟例外() {
		twseBatch.answer = date -> {
			throw new IllegalStateException("模擬程式錯誤");
		};

		assertThatCode(() -> service.runRound(Market.TWSE, Trigger.STARTUP)).doesNotThrowAnyException();
		assertThat(service.runRound(Market.TWSE, Trigger.SCHEDULED).mode()).isEqualTo(Mode.ERROR);
	}

	// ===== 時區 =====

	@Test
	void 主機時區是UTC時_今天仍是台北的日期() {
		TimeZone original = TimeZone.getDefault();
		try {
			TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
			// UTC 11/24 16:30 = 台北 11/25 00:30：UTC 的今天是 11/24，台北的今天是 11/25
			QuoteRefreshService utcHost = newService(new MutableClock(Instant.parse("2026-11-24T16:30:00Z")));

			utcHost.runRound(Market.TWSE, Trigger.SCHEDULED);

			assertThat(twseBatch.asked).containsExactly(LocalDate.of(2026, 11, 25));
		} finally {
			TimeZone.setDefault(original);
		}
	}

	// ===== 補舊持股市場 =====

	@Test
	void 每一輪都會補舊持股的市場_快取有就不用問官方() {
		holdingRepository.saveAndFlush(new Holding("2330", null, null, 1, new BigDecimal("1000.00"),
				LocalDateTime.of(2026, 11, 20, 10, 0)));
		twseBatch.answer = date -> batchOf(date, Market.TWSE, "2330", "2050.00");

		service.runRound(Market.TWSE, Trigger.SCHEDULED);

		Holding holding = holdingRepository.findAll().get(0);
		assertThat(holding.getMarket()).isEqualTo(Market.TWSE);
		assertThat(holding.getName()).isEqualTo("名稱2330");
		verifyNoInteractions(marketResolver);
	}
}
