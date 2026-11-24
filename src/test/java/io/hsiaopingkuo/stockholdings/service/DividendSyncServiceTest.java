package io.hsiaopingkuo.stockholdings.service;

import static io.hsiaopingkuo.stockholdings.service.DividendTestSupport.addTrade;
import static io.hsiaopingkuo.stockholdings.service.DividendTestSupport.esunDetail;
import static io.hsiaopingkuo.stockholdings.service.DividendTestSupport.holdWithBuy;
import static io.hsiaopingkuo.stockholdings.service.DividendTestSupport.rowOf;
import static io.hsiaopingkuo.stockholdings.service.DividendTestSupport.tpexRows2025;
import static io.hsiaopingkuo.stockholdings.service.DividendTestSupport.twseRows2025;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.hsiaopingkuo.stockholdings.calc.DividendKind;
import io.hsiaopingkuo.stockholdings.calc.DividendSource;
import io.hsiaopingkuo.stockholdings.calc.TradeSide;
import io.hsiaopingkuo.stockholdings.entity.DividendDetailStatus;
import io.hsiaopingkuo.stockholdings.entity.DividendEvent;
import io.hsiaopingkuo.stockholdings.entity.DividendFetchRange;
import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.quote.DividendDetailResult;
import io.hsiaopingkuo.stockholdings.quote.DividendListResult;
import io.hsiaopingkuo.stockholdings.quote.DividendListRow;
import io.hsiaopingkuo.stockholdings.repository.DividendEventRepository;
import io.hsiaopingkuo.stockholdings.repository.DividendFetchRangeRepository;
import io.hsiaopingkuo.stockholdings.repository.HoldingRepository;
import io.hsiaopingkuo.stockholdings.repository.TradeRepository;
import io.hsiaopingkuo.stockholdings.service.DividendSyncResult.Outcome;
import io.hsiaopingkuo.stockholdings.service.DividendTestSupport.FakeDetailClient;
import io.hsiaopingkuo.stockholdings.service.DividendTestSupport.FakeListClient;
import io.hsiaopingkuo.stockholdings.service.DividendTestSupport.Range;
import io.hsiaopingkuo.stockholdings.service.RefreshTestSupport.MutableClock;
import io.hsiaopingkuo.stockholdings.service.RefreshTestSupport.RecordingPause;

/**
 * 除權息同步一輪（第 6 題第 4b 階段）：假的 client 回 4a 的實測 JSON，資料庫用測試的 H2 + Flyway（含 V5）。
 * 不連網、不真的等待。
 */
@DataJpaTest
@Import(DividendEventWriter.class)
// 測試本身不包交易：同步時寫入用 DividendEventWriter 自己的短交易，要真的 commit 才測得出「官方 API 不在交易裡」的流程；
// 資料由「清空資料」負責清掉
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DividendSyncServiceTest {

	private static final LocalDate JAN_1_2025 = LocalDate.of(2025, 1, 1);
	private static final LocalDate DEC_31_2025 = LocalDate.of(2025, 12, 31);
	private static final LocalDate JAN_1_2026 = LocalDate.of(2026, 1, 1);
	private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);
	private static final LocalDate ESUN_EX_DATE = LocalDate.of(2025, 7, 22);

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

	private MutableClock clock;
	private RecordingPause pause;
	private FakeListClient twseList;
	private FakeListClient tpexList;
	private FakeDetailClient detailClient;
	private DividendSyncService service;

	@BeforeEach
	void setUp() {
		// 台北 2026-11-25 15:00
		clock = new MutableClock(TODAY.atTime(15, 0));
		pause = new RecordingPause();
		// 預設：2025 有實測資料，2026 沒有除權息（實測樣本只有 2025-06～07）
		twseList = new FakeListClient(Market.TWSE,
				year -> year == 2025 ? DividendListResult.ok(twseRows2025()) : DividendListResult.noData());
		tpexList = new FakeListClient(Market.TPEX,
				year -> year == 2025 ? DividendListResult.ok(tpexRows2025()) : DividendListResult.noData());
		detailClient = new FakeDetailClient(
				stockNo -> stockNo.equals("2884") ? esunDetail() : DividendDetailResult.notFound());
		setUpServiceWithClock();
	}

	@BeforeEach
	@AfterEach
	void 清空資料() {
		eventRepository.deleteAllInBatch();
		rangeRepository.deleteAllInBatch();
		// 交易設了 ON DELETE CASCADE，刪持股時一併刪除
		holdingRepository.deleteAll();
	}

	private void hold(String code, Market market, LocalDate date, long shares) {
		holdWithBuy(holdingRepository, tradeRepository, code, market, date, shares, "100.00");
	}

	private DividendEvent event(Market market, String code, LocalDate exDate) {
		return eventRepository.findAll().stream()
				.filter(event -> event.getMarket() == market && event.getCode().equals(code)
						&& event.getExDate().equals(exDate))
				.findFirst()
				.orElseThrow();
	}

	private DividendEvent esun() {
		return event(Market.TWSE, "2884", ESUN_EX_DATE);
	}

	private Map<Integer, Boolean> rangesOf(Market market) {
		Map<Integer, Boolean> ranges = new TreeMap<>();
		for (DividendFetchRange range : rangeRepository.findByMarket(market)) {
			ranges.put(range.getFetchYear(), range.isCompleted());
		}
		return ranges;
	}

	// ===== 要抓哪幾年 =====

	@Test
	void 有一筆2025年6月的交易_兩個市場各抓2025和2026_2025抓完_2026沒抓完() {
		hold("2330", Market.TWSE, LocalDate.of(2025, 6, 10), 1500);

		List<DividendSyncResult> results = service.syncAll();

		List<Range> expected = List.of(new Range(JAN_1_2025, DEC_31_2025), new Range(JAN_1_2026, TODAY));
		assertThat(twseList.asked).isEqualTo(expected);
		assertThat(tpexList.asked).isEqualTo(expected);
		assertThat(rangesOf(Market.TWSE)).isEqualTo(Map.of(2025, true, 2026, false));
		assertThat(rangesOf(Market.TPEX)).isEqualTo(Map.of(2025, true, 2026, false));
		assertThat(results).extracting(DividendSyncResult::market, DividendSyncResult::outcome,
				DividendSyncResult::fetchedYears, DividendSyncResult::eventsInserted)
				.containsExactly(
						tuple(Market.TWSE, Outcome.COMPLETED, List.of(2025, 2026), 673),
						tuple(Market.TPEX, Outcome.COMPLETED, List.of(2025, 2026), 481));
		assertThat(eventRepository.count()).isEqualTo(673 + 481);
	}

	@Test
	void 再跑一輪_2025不再抓_2026重抓_明細不會被清掉() {
		hold("2884", Market.TWSE, LocalDate.of(2025, 7, 1), 2000);
		service.syncAll();
		twseList.asked.clear();
		tpexList.asked.clear();
		detailClient.asked.clear();

		List<DividendSyncResult> results = service.syncAll();

		assertThat(twseList.asked).containsExactly(new Range(JAN_1_2026, TODAY));
		assertThat(tpexList.asked).containsExactly(new Range(JAN_1_2026, TODAY));
		assertThat(detailClient.asked).isEmpty();
		assertThat(results).allSatisfy(result -> assertThat(result.eventsInserted()).isZero());
		assertThat(esun().getDetailStatus()).isEqualTo(DividendDetailStatus.FOUND);
		assertThat(esun().getSource()).isEqualTo(DividendSource.OFFICIAL);
	}

	@Test
	void 重抓今年時_已經查到的明細保留_不再查() {
		// 2025-08-01：2025 是今年，每一輪都重抓
		clock = new MutableClock(LocalDateTime.of(2025, 8, 1, 15, 0));
		setUpServiceWithClock();
		hold("2884", Market.TWSE, LocalDate.of(2025, 7, 1), 2000);
		service.syncMarket(Market.TWSE);
		detailClient.asked.clear();

		DividendSyncResult again = service.syncMarket(Market.TWSE);

		assertThat(twseList.asked).hasSize(2);
		assertThat(again.eventsInserted()).isZero();
		assertThat(again.eventsUpdated()).isZero();
		assertThat(detailClient.asked).isEmpty();
		DividendEvent esun = esun();
		assertThat(esun.getDetailStatus()).isEqualTo(DividendDetailStatus.FOUND);
		assertThat(esun.getSource()).isEqualTo(DividendSource.OFFICIAL);
		assertThat(esun.getCashPerShare()).isEqualByComparingTo("1.2");
		assertThat(esun.getStockPerThousand()).isEqualByComparingTo("10");
		assertThat(rangesOf(Market.TWSE)).isEqualTo(Map.of(2025, false));
	}

	@Test
	void 重抓今年時_列表裡不見的事件不刪除() {
		clock = new MutableClock(LocalDateTime.of(2025, 8, 1, 15, 0));
		setUpServiceWithClock();
		hold("2330", Market.TWSE, LocalDate.of(2025, 6, 10), 1500);
		service.syncMarket(Market.TWSE);
		List<DividendListRow> without2330 = twseRows2025().stream().filter(row -> !row.code().equals("2330")).toList();
		twseList.answer = year -> DividendListResult.ok(without2330);

		service.syncMarket(Market.TWSE);

		assertThat(event(Market.TWSE, "2330", LocalDate.of(2025, 6, 12)).getCashPerShare())
				.isEqualByComparingTo("4.50002");
	}

	/** 用目前的 clock 和假的 client 建立 service；換了 clock 的測試要再呼叫一次。 */
	private void setUpServiceWithClock() {
		service = new DividendSyncService(Map.of(Market.TWSE, twseList, Market.TPEX, tpexList), detailClient, writer,
				eventRepository, rangeRepository, tradeRepository, pause, new Random(1), RefreshTestSupport.SETTINGS,
				clock);
	}

	@Test
	void 沒有資料的年份_一樣記成抓過() {
		hold("2330", Market.TWSE, LocalDate.of(2025, 6, 10), 1500);

		service.syncMarket(Market.TWSE);

		// 2026 回 NO_DATA：今年沒有除權息，也要有紀錄才分得出「還沒抓」
		assertThat(rangeRepository.findByMarketAndFetchYear(Market.TWSE, 2026)).isPresent();
	}

	@Test
	void 沒有任何交易_不發請求() {
		List<DividendSyncResult> results = service.syncAll();

		assertThat(results).extracting(DividendSyncResult::outcome).containsOnly(Outcome.NO_TRADES);
		assertThat(twseList.asked).isEmpty();
		assertThat(tpexList.asked).isEmpty();
		assertThat(rangeRepository.count()).isZero();
	}

	// ===== 補抓排程用的判斷（第 4c 階段）=====

	@Test
	void 有沒有缺的年份_沒交易時沒有_同步完沒有_補登更早的交易後有() {
		assertThat(service.hasMissingYears(Market.TWSE)).isFalse();

		hold("2330", Market.TWSE, LocalDate.of(2025, 6, 10), 1500);
		assertThat(service.hasMissingYears(Market.TWSE)).isTrue();

		service.syncAll();
		// 2025 抓完、2026 沒抓完：兩年都有紀錄，就不算缺
		assertThat(service.hasMissingYears(Market.TWSE)).isFalse();
		assertThat(service.hasMissingYears(Market.TPEX)).isFalse();

		hold("2884", Market.TWSE, LocalDate.of(2023, 3, 1), 1000);
		assertThat(service.hasMissingYears(Market.TWSE)).isTrue();
		assertThat(service.hasMissingYears(Market.TPEX)).isTrue();
	}

	// ===== 列表失敗、被擋 =====

	@Test
	void 列表失敗_那一年不記錄_其他年份照常_下一輪再抓() {
		hold("2330", Market.TWSE, LocalDate.of(2025, 6, 10), 1500);
		twseList.answer = year -> year == 2025 ? DividendListResult.failed() : DividendListResult.noData();

		DividendSyncResult result = service.syncMarket(Market.TWSE);

		assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
		assertThat(result.failedYears()).containsExactly(2025);
		assertThat(result.fetchedYears()).containsExactly(2026);
		assertThat(rangesOf(Market.TWSE)).isEqualTo(Map.of(2026, false));

		twseList.answer = year -> year == 2025 ? DividendListResult.ok(twseRows2025()) : DividendListResult.noData();
		twseList.asked.clear();
		service.syncMarket(Market.TWSE);

		assertThat(twseList.asked).contains(new Range(JAN_1_2025, DEC_31_2025));
		assertThat(rangesOf(Market.TWSE)).isEqualTo(Map.of(2025, true, 2026, false));
	}

	@Test
	void 列表被擋_這個市場停止並回報被擋_另一個市場照常() {
		hold("2884", Market.TWSE, LocalDate.of(2025, 7, 1), 2000);
		twseList.answer = year -> DividendListResult.blocked();

		List<DividendSyncResult> results = service.syncAll();

		assertThat(results.get(0).outcome()).isEqualTo(Outcome.BLOCKED);
		assertThat(results.get(0).blocked()).isTrue();
		// 2025 被擋後，2026 和明細都不再送
		assertThat(twseList.asked).containsExactly(new Range(JAN_1_2025, DEC_31_2025));
		assertThat(detailClient.asked).isEmpty();
		assertThat(rangesOf(Market.TWSE)).isEmpty();
		assertThat(results.get(1).outcome()).isEqualTo(Outcome.COMPLETED);
		assertThat(rangesOf(Market.TPEX)).isEqualTo(Map.of(2025, true, 2026, false));
	}

	// ===== 上市明細 =====

	@Test
	void 明細_持有2884權息才查_查到後是官方1點2元和10股_2330息不查_沒人持有的權息不查() {
		hold("2884", Market.TWSE, LocalDate.of(2025, 7, 1), 2000);
		hold("2330", Market.TWSE, LocalDate.of(2025, 6, 10), 1500);

		DividendSyncResult result = service.syncMarket(Market.TWSE);

		assertThat(detailClient.asked).containsExactly("2884");
		assertThat(result.detailsQueried()).isEqualTo(1);
		assertThat(result.detailsFound()).isEqualTo(1);
		DividendEvent esun = esun();
		assertThat(esun.getDetailStatus()).isEqualTo(DividendDetailStatus.FOUND);
		assertThat(esun.getSource()).isEqualTo(DividendSource.OFFICIAL);
		assertThat(esun.getCashPerShare()).isEqualByComparingTo("1.2");
		assertThat(esun.getStockPerThousand()).isEqualByComparingTo("10");
		assertThat(esun.getDetailCheckedAt()).isEqualTo(TODAY.atTime(15, 0));
		// 2330 只除息：列表的值就是官方值，不需要明細
		DividendEvent tsmc = event(Market.TWSE, "2330", LocalDate.of(2025, 6, 12));
		assertThat(tsmc.getDetailStatus()).isEqualTo(DividendDetailStatus.NOT_NEEDED);
		assertThat(tsmc.getSource()).isEqualTo(DividendSource.OFFICIAL);
		// 其他權息事件沒有人持有：維持待查、沒查過
		assertThat(eventRepository.findAll()).filteredOn(event -> event.getKind() == DividendKind.CASH_AND_STOCK
				&& !event.getCode().equals("2884"))
				.isNotEmpty()
				.allSatisfy(event -> {
					assertThat(event.getDetailStatus()).isEqualTo(DividendDetailStatus.PENDING);
					assertThat(event.getDetailCheckedAt()).isNull();
					assertThat(event.getSource()).isEqualTo(DividendSource.UNAVAILABLE);
				});
	}

	@Test
	void 明細_除權息日不晚於第一筆交易日_不查() {
		// 除權息當天才買：領不到，這次除權息不影響這檔
		hold("2884", Market.TWSE, ESUN_EX_DATE, 2000);

		service.syncMarket(Market.TWSE);

		assertThat(detailClient.asked).isEmpty();
		assertThat(esun().getDetailStatus()).isEqualTo(DividendDetailStatus.PENDING);
	}

	@Test
	void 明細_已經賣光的持股也算有人持有() {
		Holding esun = holdWithBuy(holdingRepository, tradeRepository, "2884", Market.TWSE, LocalDate.of(2025, 7, 1),
				2000, "30.00");
		addTrade(tradeRepository, esun, TradeSide.SELL, LocalDate.of(2025, 9, 1), 2020, "33.00");

		service.syncMarket(Market.TWSE);

		assertThat(detailClient.asked).containsExactly("2884");
	}

	@Test
	void 明細查不到_標成查不到_隔天也不再查() {
		hold("2884", Market.TWSE, LocalDate.of(2025, 7, 1), 2000);
		detailClient.answer = stockNo -> DividendDetailResult.notFound();

		DividendSyncResult result = service.syncMarket(Market.TWSE);
		clock.advance(Duration.ofDays(1));
		service.syncMarket(Market.TWSE);

		assertThat(result.detailsNotFound()).isEqualTo(1);
		assertThat(detailClient.asked).containsExactly("2884");
		assertThat(esun().getDetailStatus()).isEqualTo(DividendDetailStatus.NOT_FOUND);
		assertThat(esun().getSource()).isEqualTo(DividendSource.UNAVAILABLE);
	}

	@Test
	void 明細失敗_同一天不重查_隔天再查() {
		hold("2884", Market.TWSE, LocalDate.of(2025, 7, 1), 2000);
		detailClient.answer = stockNo -> DividendDetailResult.failed();

		DividendSyncResult first = service.syncMarket(Market.TWSE);
		clock.advance(Duration.ofHours(2));
		service.syncMarket(Market.TWSE);

		assertThat(first.detailsFailed()).isEqualTo(1);
		assertThat(detailClient.asked).containsExactly("2884");
		assertThat(esun().getDetailStatus()).isEqualTo(DividendDetailStatus.FAILED);

		clock.advance(Duration.ofDays(1));
		detailClient.answer = stockNo -> esunDetail();
		service.syncMarket(Market.TWSE);

		assertThat(detailClient.asked).containsExactly("2884", "2884");
		assertThat(esun().getDetailStatus()).isEqualTo(DividendDetailStatus.FOUND);
	}

	@Test
	void 明細被擋_停止並回報被擋_狀態不變() {
		hold("2884", Market.TWSE, LocalDate.of(2025, 7, 1), 2000);
		detailClient.answer = stockNo -> DividendDetailResult.blocked();

		DividendSyncResult result = service.syncMarket(Market.TWSE);

		assertThat(result.outcome()).isEqualTo(Outcome.BLOCKED);
		assertThat(result.detailsQueried()).isEqualTo(1);
		assertThat(esun().getDetailStatus()).isEqualTo(DividendDetailStatus.PENDING);
		assertThat(esun().getDetailCheckedAt()).isNull();
	}

	// ===== 同一檔同一天出現兩列（設計決定 7-B）=====

	@Test
	void 同一檔同一天出現兩列_存成無法取得_其他列照常寫入() {
		hold("2330", Market.TWSE, LocalDate.of(2025, 6, 10), 1500);
		DividendListRow tsmc = rowOf(twseRows2025(), "2330");
		DividendListRow secondTsmc = new DividendListRow(Market.TWSE, tsmc.exDate(), "2330", tsmc.name(),
				DividendKind.STOCK, tsmc.prevClose(), tsmc.exRightReference(), new BigDecimal("1.000000"),
				tsmc.dividendReference(), null, null, tsmc.detailStockNo(), tsmc.detailDate());
		List<DividendListRow> rows = new ArrayList<>(twseRows2025());
		rows.add(secondTsmc);
		twseList.answer = year -> year == 2025 ? DividendListResult.ok(rows) : DividendListResult.noData();

		DividendSyncResult result = service.syncMarket(Market.TWSE);

		assertThat(result.eventsInserted()).isEqualTo(673);
		DividendEvent saved = event(Market.TWSE, "2330", LocalDate.of(2025, 6, 12));
		assertThat(saved.getSource()).isEqualTo(DividendSource.UNAVAILABLE);
		assertThat(saved.getCashPerShare()).isNull();
		assertThat(saved.getDetailStatus()).isEqualTo(DividendDetailStatus.NOT_NEEDED);
		assertThat(detailClient.asked).isEmpty();
		assertThat(esun().getSource()).isEqualTo(DividendSource.UNAVAILABLE);
		assertThat(rangesOf(Market.TWSE)).isEqualTo(Map.of(2025, true, 2026, false));
	}

	// ===== 請求間隔 =====

	@Test
	void 每兩個請求之間都隨機等3點2到5點8秒_每個市場的第一個請求不用等() {
		hold("2884", Market.TWSE, LocalDate.of(2025, 7, 1), 2000);

		service.syncAll();

		// 上市：2025、2026 列表 + 2884 明細 = 3 個請求、等 2 次；上櫃：2 個請求、等 1 次
		assertThat(pause.waits).hasSize(3)
				.allSatisfy(wait -> assertThat(wait).isBetween(Duration.ofMillis(3200), Duration.ofMillis(5800)));
	}
}
