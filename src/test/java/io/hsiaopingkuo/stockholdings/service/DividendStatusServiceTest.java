package io.hsiaopingkuo.stockholdings.service;

import static io.hsiaopingkuo.stockholdings.service.DividendTestSupport.rowOf;
import static io.hsiaopingkuo.stockholdings.service.DividendTestSupport.twseRows2025;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.hsiaopingkuo.stockholdings.calc.DividendDataStatus;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.repository.DividendEventRepository;
import io.hsiaopingkuo.stockholdings.repository.DividendFetchRangeRepository;
import io.hsiaopingkuo.stockholdings.service.DividendStatusService.HoldingScope;

/** 每檔持股的股利資料狀態（第 6 題設計決定 7-D）：從資料庫讀已抓範圍和待查的明細。 */
@DataJpaTest
@Import(DividendEventWriter.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DividendStatusServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 15, 0);
	private static final LocalDate JULY_1_2025 = LocalDate.of(2025, 7, 1);

	@Autowired
	private DividendEventWriter writer;

	@Autowired
	private DividendEventRepository eventRepository;

	@Autowired
	private DividendFetchRangeRepository rangeRepository;

	private DividendStatusService service;

	@BeforeEach
	void setUp() {
		service = new DividendStatusService(rangeRepository, eventRepository,
				Clock.fixed(NOW.atZone(ZoneId.of("Asia/Taipei")).toInstant(), ZoneId.of("Asia/Taipei")));
	}

	@BeforeEach
	@AfterEach
	void 清空資料() {
		eventRepository.deleteAllInBatch();
		rangeRepository.deleteAllInBatch();
	}

	private DividendDataStatus statusOf(String code, Market market, LocalDate firstTradeDate) {
		Map<String, DividendDataStatus> statuses = service.statusByCode(
				List.of(new HoldingScope(code, market, firstTradeDate)));
		return statuses.get(code);
	}

	/** 兩個市場的 2025（抓完）、2026（抓過）都記好。 */
	private void markBothMarketsFetched() {
		for (Market market : Market.values()) {
			writer.markYearFetched(market, 2025, true, NOW);
			writer.markYearFetched(market, 2026, false, NOW);
		}
	}

	@Test
	void 還沒抓_待更新() {
		assertThat(statusOf("2330", Market.TWSE, JULY_1_2025)).isEqualTo(DividendDataStatus.PENDING);
	}

	@Test
	void 過去的年份抓過但沒抓完_待更新() {
		writer.markYearFetched(Market.TWSE, 2025, false, NOW);
		writer.markYearFetched(Market.TWSE, 2026, false, NOW);

		assertThat(statusOf("2330", Market.TWSE, JULY_1_2025)).isEqualTo(DividendDataStatus.PENDING);
	}

	@Test
	void 抓完_完整() {
		markBothMarketsFetched();

		assertThat(statusOf("2330", Market.TWSE, JULY_1_2025)).isEqualTo(DividendDataStatus.COMPLETE);
	}

	@Test
	void 只看持股自己的市場_另一個市場沒抓不影響() {
		writer.markYearFetched(Market.TPEX, 2025, true, NOW);
		writer.markYearFetched(Market.TPEX, 2026, false, NOW);

		assertThat(statusOf("8069", Market.TPEX, JULY_1_2025)).isEqualTo(DividendDataStatus.COMPLETE);
		// 市場是 NULL（第 2 題的舊資料還沒補到）：兩個市場都要抓過
		assertThat(statusOf("8069", null, JULY_1_2025)).isEqualTo(DividendDataStatus.PENDING);
	}

	@Test
	void 有明細待查_待更新_除權息日在第一筆交易之前則不影響() {
		markBothMarketsFetched();
		// 2884 2025-07-22 權息：列表算不出官方值，存進去是「待查」
		writer.saveListYear(Market.TWSE, 2025, List.of(rowOf(twseRows2025(), "2884")), true, NOW);

		assertThat(statusOf("2884", Market.TWSE, JULY_1_2025)).isEqualTo(DividendDataStatus.PENDING);
		assertThat(statusOf("2884", Market.TWSE, LocalDate.of(2025, 7, 22))).isEqualTo(DividendDataStatus.COMPLETE);
	}

	@Test
	void 多檔一起查_順序和傳入的相同() {
		markBothMarketsFetched();
		writer.saveListYear(Market.TWSE, 2025, List.of(rowOf(twseRows2025(), "2884")), true, NOW);

		Map<String, DividendDataStatus> statuses = service.statusByCode(List.of(
				new HoldingScope("2884", Market.TWSE, JULY_1_2025),
				new HoldingScope("2330", Market.TWSE, JULY_1_2025),
				new HoldingScope("9999", Market.TWSE, null)));

		assertThat(statuses).containsExactly(
				Map.entry("2884", DividendDataStatus.PENDING),
				Map.entry("2330", DividendDataStatus.COMPLETE),
				Map.entry("9999", DividendDataStatus.COMPLETE));
	}
}
