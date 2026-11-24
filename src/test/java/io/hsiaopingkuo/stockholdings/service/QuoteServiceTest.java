package io.hsiaopingkuo.stockholdings.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.entity.Quote;
import io.hsiaopingkuo.stockholdings.quote.DailyQuote;
import io.hsiaopingkuo.stockholdings.repository.QuoteRepository;
import jakarta.persistence.EntityManager;

/** 行情快取的寫入規則：兩個日期分開記、舊資料不覆蓋新資料。用真的資料庫（H2 + Flyway V3）。 */
@DataJpaTest
@Import({ QuoteService.class, QuoteServiceTest.FixedClockConfig.class })
class QuoteServiceTest {

	private static final LocalDate NOV_20 = LocalDate.of(2026, 11, 20);
	private static final LocalDate NOV_23 = LocalDate.of(2026, 11, 23);
	private static final LocalDate NOV_24 = LocalDate.of(2026, 11, 24);

	@TestConfiguration
	static class FixedClockConfig {
		@Bean
		Clock clock() {
			// UTC 06:00 = 台北 14:00
			return Clock.fixed(Instant.parse("2026-11-24T06:00:00Z"), ZoneId.of("Asia/Taipei"));
		}
	}

	@Autowired
	private QuoteService quoteService;

	@Autowired
	private QuoteRepository quoteRepository;

	@Autowired
	private EntityManager entityManager;

	private static DailyQuote day(String code, LocalDate date, String close) {
		return DailyQuote.ofDay(code, "名稱" + code, Market.TWSE, date, close == null ? null : new BigDecimal(close));
	}

	/** 清掉 JPA 快取再讀，確認是真的寫進資料庫。 */
	private Quote reload(String code) {
		entityManager.flush();
		entityManager.clear();
		return quoteRepository.findById(code).orElseThrow();
	}

	@Test
	void 新代號_寫入收盤價與兩個日期_時間用台北時區() {
		int changed = quoteService.upsert(List.of(day("2330", NOV_23, "2030.00")));

		Quote quote = reload("2330");
		assertThat(changed).isEqualTo(1);
		assertThat(quote.getName()).isEqualTo("名稱2330");
		assertThat(quote.getMarket()).isEqualTo(Market.TWSE);
		assertThat(quote.getClosePrice()).isEqualByComparingTo("2030.00");
		assertThat(quote.getCloseDate()).isEqualTo(NOV_23);
		assertThat(quote.getLatestTradeDate()).isEqualTo(NOV_23);
		assertThat(quote.getUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 11, 24, 14, 0));
	}

	@Test
	void 批次裡是無成交_保留前一天收盤價_最近交易日更新為今天() {
		quoteService.upsert(List.of(day("2330", NOV_23, "2030.00")));

		quoteService.upsert(List.of(day("2330", NOV_24, null)));

		Quote quote = reload("2330");
		assertThat(quote.getClosePrice()).isEqualByComparingTo("2030.00");
		assertThat(quote.getCloseDate()).isEqualTo(NOV_23);
		assertThat(quote.getLatestTradeDate()).isEqualTo(NOV_24);
	}

	@Test
	void 不在當天批次裡的代號_原本的資料不變() {
		quoteService.upsert(List.of(day("2330", NOV_23, "2030.00"), day("2317", NOV_23, "200.00")));

		quoteService.upsert(List.of(day("2317", NOV_24, "201.00")));

		Quote quote = reload("2330");
		assertThat(quote.getClosePrice()).isEqualByComparingTo("2030.00");
		assertThat(quote.getCloseDate()).isEqualTo(NOV_23);
		assertThat(quote.getLatestTradeDate()).isEqualTo(NOV_23);
	}

	@Test
	void 舊的交易日不會覆蓋新的() {
		quoteService.upsert(List.of(day("2330", NOV_24, "2050.00")));

		int changed = quoteService.upsert(List.of(day("2330", NOV_23, "2030.00")));

		Quote quote = reload("2330");
		assertThat(changed).isZero();
		assertThat(quote.getClosePrice()).isEqualByComparingTo("2050.00");
		assertThat(quote.getCloseDate()).isEqualTo(NOV_24);
		assertThat(quote.getLatestTradeDate()).isEqualTo(NOV_24);
	}

	@Test
	void 交易日較新但收盤日較舊_只更新最近交易日_收盤價保留較新的() {
		// 表裡已有 11/23 的收盤價；逐檔查到「11/24 無成交、最後一次成交在 11/20」
		quoteService.upsert(List.of(day("2330", NOV_23, "2030.00")));

		quoteService.upsert(List.of(new DailyQuote("2330", "台積電", Market.TWSE, NOV_24, new BigDecimal("1990.00"), NOV_20)));

		Quote quote = reload("2330");
		assertThat(quote.getClosePrice()).isEqualByComparingTo("2030.00");
		assertThat(quote.getCloseDate()).isEqualTo(NOV_23);
		assertThat(quote.getLatestTradeDate()).isEqualTo(NOV_24);
	}

	@Test
	void 從來沒有成交_收盤價和收盤日都是null() {
		quoteService.upsert(List.of(day("9999", NOV_24, null)));

		Quote quote = reload("9999");
		assertThat(quote.getClosePrice()).isNull();
		assertThat(quote.getCloseDate()).isNull();
		assertThat(quote.getLatestTradeDate()).isEqualTo(NOV_24);
	}

	@Test
	void 同一份資料重複寫入_不算更新_名稱是null時不洗掉原本的名稱() {
		quoteService.upsert(List.of(day("2330", NOV_23, "2030.00")));

		int changed = quoteService.upsert(List.of(day("2330", NOV_23, "2030.0")));
		quoteService.upsert(List.of(DailyQuote.ofDay("2330", null, Market.TWSE, NOV_23, new BigDecimal("2030.00"))));

		assertThat(changed).isZero();
		assertThat(reload("2330").getName()).isEqualTo("名稱2330");
	}
}
