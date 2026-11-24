package io.hsiaopingkuo.stockholdings.service;

import static io.hsiaopingkuo.stockholdings.service.DividendTestSupport.rowOf;
import static io.hsiaopingkuo.stockholdings.service.DividendTestSupport.tpexRows2025;
import static io.hsiaopingkuo.stockholdings.service.DividendTestSupport.twseRows2025;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

import io.hsiaopingkuo.stockholdings.calc.DividendEventInput;
import io.hsiaopingkuo.stockholdings.calc.DividendKind;
import io.hsiaopingkuo.stockholdings.calc.DividendSource;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.quote.DividendListRow;
import io.hsiaopingkuo.stockholdings.repository.DividendEventRepository;
import io.hsiaopingkuo.stockholdings.repository.DividendFetchRangeRepository;

/** 從資料庫讀除權息事件給重播用（第 6 題第 4b 階段）。 */
@DataJpaTest
@Import({ DatabaseDividendEventProvider.class, DividendEventWriter.class })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DatabaseDividendEventProviderTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 15, 0);

	@Autowired
	private DatabaseDividendEventProvider provider;

	@Autowired
	private DividendEventWriter writer;

	@Autowired
	private DividendEventRepository eventRepository;

	@Autowired
	private DividendFetchRangeRepository rangeRepository;

	@BeforeEach
	@AfterEach
	void 清空資料() {
		eventRepository.deleteAllInBatch();
		rangeRepository.deleteAllInBatch();
	}

	@Test
	void 依代號分組_依除權息日排序_無法取得的事件也提供() {
		DividendListRow tsmcJune = rowOf(twseRows2025(), "2330");
		// 同一檔另一次除權息（日期比 6 月早），確認排序
		DividendListRow tsmcMarch = new DividendListRow(Market.TWSE, LocalDate.of(2025, 3, 18), "2330",
				tsmcJune.name(), tsmcJune.kind(), tsmcJune.prevClose(), tsmcJune.exRightReference(),
				tsmcJune.rightsPlusDividend(), tsmcJune.dividendReference(), null, null, "2330",
				LocalDate.of(2025, 3, 18));
		writer.saveListYear(Market.TWSE, 2025, List.of(tsmcJune, tsmcMarch, rowOf(twseRows2025(), "2884")), true,
				NOW);
		writer.saveListYear(Market.TPEX, 2025, List.of(rowOf(tpexRows2025(), "8069")), true, NOW);

		Map<String, List<DividendEventInput>> events = provider.eventsByCode(List.of("2330", "2884", "9999"));

		assertThat(events).containsOnlyKeys("2330", "2884");
		assertThat(events.get("2330")).extracting(DividendEventInput::exDate)
				.containsExactly(LocalDate.of(2025, 3, 18), LocalDate.of(2025, 6, 12));
		assertThat(events.get("2330").get(1).cashPerShare()).isEqualByComparingTo("4.50002");
		assertThat(events.get("2330").get(1).source()).isEqualTo(DividendSource.OFFICIAL);
		// 2884 權息沒有明細：無法取得，數字是 null，但事件仍要給重播，明細才會列出這一筆
		DividendEventInput esun = events.get("2884").get(0);
		assertThat(esun.kind()).isEqualTo(DividendKind.CASH_AND_STOCK);
		assertThat(esun.source()).isEqualTo(DividendSource.UNAVAILABLE);
		assertThat(esun.cashPerShare()).isNull();
		assertThat(esun.stockPerThousand()).isNull();
	}

	@Test
	void 沒有代號_回傳空的() {
		assertThat(provider.eventsByCode(List.of())).isEmpty();
	}
}
