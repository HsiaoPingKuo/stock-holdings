package io.hsiaopingkuo.stockholdings.service;

import static io.hsiaopingkuo.stockholdings.service.DividendTestSupport.esunDetail;
import static io.hsiaopingkuo.stockholdings.service.DividendTestSupport.holdWithBuy;
import static io.hsiaopingkuo.stockholdings.service.DividendTestSupport.tpexRows2025;
import static io.hsiaopingkuo.stockholdings.service.DividendTestSupport.twseRows2025;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Random;

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

import io.hsiaopingkuo.stockholdings.calc.DividendDetail;
import io.hsiaopingkuo.stockholdings.calc.DividendSource;
import io.hsiaopingkuo.stockholdings.calc.HoldingReplayCalculator;
import io.hsiaopingkuo.stockholdings.calc.HoldingReplayResult;
import io.hsiaopingkuo.stockholdings.calc.TradeInput;
import io.hsiaopingkuo.stockholdings.config.QuoteProperties;
import io.hsiaopingkuo.stockholdings.dto.HoldingListItem;
import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.entity.Trade;
import io.hsiaopingkuo.stockholdings.quote.DividendDetailResult;
import io.hsiaopingkuo.stockholdings.quote.DividendListResult;
import io.hsiaopingkuo.stockholdings.quote.MarketResolver;
import io.hsiaopingkuo.stockholdings.repository.DividendEventRepository;
import io.hsiaopingkuo.stockholdings.repository.DividendFetchRangeRepository;
import io.hsiaopingkuo.stockholdings.repository.HoldingRepository;
import io.hsiaopingkuo.stockholdings.repository.TradeRepository;
import io.hsiaopingkuo.stockholdings.service.DividendTestSupport.FakeDetailClient;
import io.hsiaopingkuo.stockholdings.service.DividendTestSupport.FakeListClient;
import io.hsiaopingkuo.stockholdings.service.RefreshTestSupport.RecordingPause;

/**
 * 題目的除權息例子（設計決定第 12 節），從頭接到尾：
 * 假的 client 回 4a 的實測 JSON → 同步一輪寫進資料庫 → DatabaseDividendEventProvider 讀出來 → 重播計算；
 * 股數、/api/v1 的 buy_price 另外透過 HoldingService（和 /api/v1 同一套組裝）驗證。
 */
@DataJpaTest
@Import({ HoldingService.class, QuoteService.class, StockIdentityResolver.class, DatabaseDividendEventProvider.class,
		DividendEventWriter.class,
		DividendReplayIntegrationTest.FixedClockConfig.class })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DividendReplayIntegrationTest {

	private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 15, 0);
	private static final LocalDate JULY_1_2025 = LocalDate.of(2025, 7, 1);

	@TestConfiguration
	// HoldingService 要讀 quote.request-budget（測試用 application.properties 有設定）
	@EnableConfigurationProperties(QuoteProperties.class)
	static class FixedClockConfig {
		@Bean
		Clock clock() {
			return Clock.fixed(NOW.atZone(TAIPEI).toInstant(), TAIPEI);
		}
	}

	@Autowired
	private HoldingService holdingService;

	@Autowired
	private DatabaseDividendEventProvider provider;

	@Autowired
	private DividendEventWriter writer;

	@Autowired
	private DividendEventRepository eventRepository;

	@Autowired
	private DividendFetchRangeRepository rangeRepository;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private Clock clock;

	// HoldingService 的依賴；這裡只讀資料，不會用到官方查詢
	@MockitoBean
	private MarketResolver marketResolver;

	private FakeDetailClient detailClient;
	private DividendSyncService syncService;

	@BeforeEach
	void setUp() {
		FakeListClient twseList = new FakeListClient(Market.TWSE,
				year -> year == 2025 ? DividendListResult.ok(twseRows2025()) : DividendListResult.noData());
		FakeListClient tpexList = new FakeListClient(Market.TPEX,
				year -> year == 2025 ? DividendListResult.ok(tpexRows2025()) : DividendListResult.noData());
		detailClient = new FakeDetailClient(
				stockNo -> stockNo.equals("2884") ? esunDetail() : DividendDetailResult.notFound());
		syncService = new DividendSyncService(Map.of(Market.TWSE, twseList, Market.TPEX, tpexList), detailClient,
				writer, eventRepository, rangeRepository, tradeRepository, new RecordingPause(), new Random(1),
				RefreshTestSupport.SETTINGS, clock);
	}

	@BeforeEach
	@AfterEach
	void 清空資料() {
		eventRepository.deleteAllInBatch();
		rangeRepository.deleteAllInBatch();
		holdingRepository.deleteAll();
	}

	/** 存好持股和買進後同步一輪，再用資料庫裡的事件重播這一檔。 */
	private HoldingReplayResult syncAndReplay(Holding holding) {
		syncService.syncAll();
		List<TradeInput> trades = tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(holding.getId()).stream()
				.map(Trade::toInput)
				.toList();
		return HoldingReplayCalculator.replay(holding.getCode(), trades,
				provider.eventsByCode(List.of(holding.getCode())).getOrDefault(holding.getCode(), List.of()), null);
	}

	private Holding buy(String code, Market market, LocalDate date, long shares, String price) {
		return holdWithBuy(holdingRepository, tradeRepository, code, market, date, shares, price);
	}

	@Test
	void 台積電2330_2025年6月10日買1500股_06月12日除息_現金股利6750() {
		HoldingReplayResult result = syncAndReplay(buy("2330", Market.TWSE, LocalDate.of(2025, 6, 10), 1500, "1000.00"));

		assertThat(result.cumulativeCashDividend()).isEqualByComparingTo("6750");
		assertThat(result.totalShares()).isEqualTo(1500);
		assertThat(result.dividends()).singleElement().satisfies(detail -> {
			assertThat(detail.exDate()).isEqualTo(LocalDate.of(2025, 6, 12));
			assertThat(detail.eligibleShares()).isEqualTo(1500);
			assertThat(detail.source()).isEqualTo(DividendSource.OFFICIAL);
		});
	}

	@Test
	void 玉山金2884_買2000股30元_明細查到_除權息後2020股_平均成本29點75() {
		Holding esun = buy("2884", Market.TWSE, JULY_1_2025, 2000, "30.00");

		HoldingReplayResult result = syncAndReplay(esun);

		assertThat(result.totalShares()).isEqualTo(2020);
		assertThat(result.averageCost()).isEqualByComparingTo("29.75");
		assertThat(result.cumulativeCashDividend()).isEqualByComparingTo("2400");
		// 接上 HoldingService（/api/v1 同一套組裝）：股數和 App 1.0 的買進價（配股後改成平均成本）
		HoldingListItem item = holdingService.findListItem(esun.getId());
		assertThat(item.totalShares()).isEqualTo(2020);
		assertThat(item.lots()).isEqualTo(2);
		assertThat(item.buyPrice()).isEqualByComparingTo("29.75");
	}

	@Test
	void 玉山金2884_買1550股_現金1860_配股15() {
		HoldingReplayResult result = syncAndReplay(buy("2884", Market.TWSE, JULY_1_2025, 1550, "30.00"));

		assertThat(result.cumulativeCashDividend()).isEqualByComparingTo("1860");
		assertThat(result.dividends()).singleElement()
				.extracting(DividendDetail::stockDividendShares).isEqualTo(15L);
		assertThat(result.totalShares()).isEqualTo(1565);
	}

	@Test
	void 元太8069上櫃_買1000股_除息4點99013930_現金股利4990() {
		HoldingReplayResult result = syncAndReplay(buy("8069", Market.TPEX, JULY_1_2025, 1000, "200.00"));

		assertThat(result.cumulativeCashDividend()).isEqualByComparingTo("4990");
		assertThat(result.totalShares()).isEqualTo(1000);
	}

	@Test
	void 玉山金2884_明細查不到_無法取得_不計入股利和配股_明細仍列出這一筆() {
		detailClient.answer = stockNo -> DividendDetailResult.notFound();
		Holding esun = buy("2884", Market.TWSE, JULY_1_2025, 2000, "30.00");

		HoldingReplayResult result = syncAndReplay(esun);

		assertThat(result.totalShares()).isEqualTo(2000);
		assertThat(result.cumulativeCashDividend()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(result.dividends()).singleElement().satisfies(detail -> {
			assertThat(detail.source()).isEqualTo(DividendSource.UNAVAILABLE);
			assertThat(detail.eligibleShares()).isEqualTo(2000);
			assertThat(detail.cashDividend()).isNull();
			assertThat(detail.stockDividendShares()).isNull();
		});
		assertThat(holdingService.findListItem(esun.getId()).totalShares()).isEqualTo(2000);
	}
}
