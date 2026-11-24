package io.hsiaopingkuo.stockholdings.service;

import static io.hsiaopingkuo.stockholdings.service.RefreshTestSupport.SETTINGS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.exception.MarketUnavailableException;
import io.hsiaopingkuo.stockholdings.exception.StockCodeNotFoundException;
import io.hsiaopingkuo.stockholdings.quote.DailyQuote;
import io.hsiaopingkuo.stockholdings.quote.MarketResolver;
import io.hsiaopingkuo.stockholdings.quote.StockLookup;
import io.hsiaopingkuo.stockholdings.repository.HoldingRepository;
import io.hsiaopingkuo.stockholdings.repository.QuoteRepository;
import io.hsiaopingkuo.stockholdings.service.RefreshTestSupport.RecordingPause;

/** 補上第 2 題舊持股的市場和名稱：先查快取，查不到才問官方。官方用假的（不連網）。 */
@DataJpaTest
@Import({ QuoteService.class, HoldingBackfillServiceTest.ClockConfig.class })
class HoldingBackfillServiceTest {

	private static final LocalDateTime CREATED = LocalDateTime.of(2026, 11, 20, 10, 0);

	@TestConfiguration
	static class ClockConfig {
		@Bean
		Clock clock() {
			return Clock.fixed(Instant.parse("2026-11-24T06:00:00Z"), RefreshTestSupport.TAIPEI);
		}
	}

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private QuoteRepository quoteRepository;

	@Autowired
	private QuoteService quoteService;

	@Autowired
	private Clock clock;

	private MarketResolver marketResolver;
	private RecordingPause pause;
	private HoldingBackfillService service;

	@BeforeEach
	void setUp() {
		marketResolver = mock(MarketResolver.class);
		pause = new RecordingPause();
		service = new HoldingBackfillService(holdingRepository, quoteService, marketResolver, pause, new Random(1),
				RefreshTestSupport.propertiesWith(SETTINGS), clock);
	}

	private void oldHolding(String code) {
		holdingRepository.saveAndFlush(new Holding(code, null, null, 1, new BigDecimal("100.00"), CREATED));
	}

	private Holding holdingOf(String code) {
		return holdingRepository.findAll().stream().filter(h -> h.getCode().equals(code)).findFirst().orElseThrow();
	}

	@Test
	void 快取有這個代號_用快取補市場和名稱_不問官方() {
		oldHolding("6488");
		quoteService.upsert(List.of(DailyQuote.ofDay("6488", "環球晶", Market.TPEX, LocalDate.of(2026, 11, 23),
				new BigDecimal("460.50"))));

		int filled = service.backfill(true);

		assertThat(filled).isEqualTo(1);
		assertThat(holdingOf("6488").getMarket()).isEqualTo(Market.TPEX);
		assertThat(holdingOf("6488").getName()).isEqualTo("環球晶");
		// updated_at 用台北時區的 clock
		assertThat(holdingOf("6488").getUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 11, 24, 14, 0));
		verifyNoInteractions(marketResolver);
	}

	@Test
	void 快取沒有_問官方補上_並把行情寫進快取() {
		oldHolding("2330");
		given(marketResolver.resolve("2330")).willReturn(
				StockLookup.found(Market.TWSE, "台積電", LocalDate.of(2026, 11, 23), new BigDecimal("2030.00")));

		service.backfill(true);

		assertThat(holdingOf("2330").getMarket()).isEqualTo(Market.TWSE);
		assertThat(quoteRepository.findById("2330")).isPresent();
	}

	@Test
	void 不允許呼叫官方時_只用快取補_查不到的留到下一輪() {
		oldHolding("2330");

		int filled = service.backfill(false);

		assertThat(filled).isZero();
		assertThat(holdingOf("2330").getMarket()).isNull();
		verifyNoInteractions(marketResolver);
	}

	@Test
	void 官方連不上_這次停止_後面的代號不再查() {
		oldHolding("2303");
		oldHolding("2330");
		given(marketResolver.resolve("2303")).willThrow(new MarketUnavailableException());

		service.backfill(true);

		verify(marketResolver, never()).resolve("2330");
		assertThat(holdingOf("2303").getMarket()).isNull();
	}

	@Test
	void 查無此代號_跳過繼續補下一檔_問官方的請求之間要等() {
		oldHolding("2303");
		oldHolding("2330");
		given(marketResolver.resolve("2303")).willThrow(new StockCodeNotFoundException("2303"));
		given(marketResolver.resolve("2330")).willReturn(
				StockLookup.found(Market.TWSE, "台積電", LocalDate.of(2026, 11, 23), new BigDecimal("2030.00")));

		service.backfill(true);

		assertThat(holdingOf("2303").getMarket()).isNull();
		assertThat(holdingOf("2330").getMarket()).isEqualTo(Market.TWSE);
		assertThat(pause.waits).hasSize(1);
	}

	@Test
	void 已經有市場的持股不會被改() {
		holdingRepository.saveAndFlush(new Holding("2330", "台積電", Market.TWSE, 1, new BigDecimal("100.00"), CREATED));

		int filled = service.backfill(true);

		assertThat(filled).isZero();
		verifyNoInteractions(marketResolver);
	}
}
