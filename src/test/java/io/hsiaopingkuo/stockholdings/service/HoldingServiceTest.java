package io.hsiaopingkuo.stockholdings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.hsiaopingkuo.stockholdings.config.QuoteProperties;
import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.entity.Quote;
import io.hsiaopingkuo.stockholdings.exception.DuplicateCodeException;
import io.hsiaopingkuo.stockholdings.exception.HoldingNotFoundException;
import io.hsiaopingkuo.stockholdings.exception.MarketUnavailableException;
import io.hsiaopingkuo.stockholdings.exception.StockCodeNotFoundException;
import io.hsiaopingkuo.stockholdings.quote.DailyQuote;
import io.hsiaopingkuo.stockholdings.quote.Deadline;
import io.hsiaopingkuo.stockholdings.quote.MarketResolver;
import io.hsiaopingkuo.stockholdings.quote.StockLookup;
import io.hsiaopingkuo.stockholdings.repository.HoldingRepository;
import io.hsiaopingkuo.stockholdings.repository.QuoteRepository;

/** Service 搭配真的資料庫（測試用 H2 記憶體模式 + Flyway）測試業務規則。 */
@DataJpaTest
@Import({ HoldingService.class, QuoteService.class, HoldingServiceTest.FixedClockConfig.class })
// 測試本身不包交易（F-01）：create() 要在「沒有交易」的情況下呼叫官方，
// 如果測試先開了交易，就測不出 create() 有沒有自己開交易；資料改由「清空資料」負責清掉
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class HoldingServiceTest {

	// UTC 06:00 = 台北 14:00；存進去的時間要是 14:00 才代表真的用台北時區
	private static final Instant FIXED = Instant.parse("2026-11-24T06:00:00Z");

	@TestConfiguration
	// HoldingService 要讀 quote.request-budget（測試用 application.properties 有設定）
	@EnableConfigurationProperties(QuoteProperties.class)
	static class FixedClockConfig {
		@Bean
		Clock clock() {
			return Clock.fixed(FIXED, ZoneId.of("Asia/Taipei"));
		}
	}

	@Autowired
	private HoldingService holdingService;

	@Autowired
	private HoldingRepository holdingRepository;

	// 官方 API 的判斷另外在 MarketResolverTest 測；這裡用假的，專心測 service 的流程，也不會連網
	@MockitoBean
	private MarketResolver marketResolver;

	@BeforeEach
	void 預設任何代號都判斷為上市() {
		given(marketResolver.resolve(anyString())).willAnswer(invocation -> StockLookup.found(
				Market.TWSE, "測試股", LocalDate.of(2026, 11, 23), new BigDecimal("10.00")));
		// 新增持股改呼叫「帶截止時間」的版本（F-01）；即時提示仍用上面那個
		given(marketResolver.resolve(anyString(), any(Deadline.class))).willAnswer(invocation -> StockLookup.found(
				Market.TWSE, "測試股", LocalDate.of(2026, 11, 23), new BigDecimal("10.00")));
	}

	// create() 不開交易（F-01），寫入會真的 commit，測試交易 rollback 不掉；每個測試前後自己清空
	@BeforeEach
	@AfterEach
	void 清空資料() {
		holdingRepository.deleteAll();
		quoteRepository.deleteAll();
	}

	// ===== 第 3 題：先查行情快取 =====

	@Autowired
	private QuoteRepository quoteRepository;

	private void cacheQuote(String code, String name, Market market) {
		quoteRepository.saveAndFlush(new Quote(DailyQuote.ofDay(code, name, market, LocalDate.of(2026, 11, 23),
				new BigDecimal("460.50")), LocalDateTime.of(2026, 11, 23, 14, 0)));
	}

	@Test
	void 行情快取有這個代號_用快取的市場和名稱新增_不呼叫官方() {
		cacheQuote("6488", "環球晶", Market.TPEX);

		Holding created = holdingService.create("6488", 1, new BigDecimal("450.00"));

		assertThat(created.getMarket()).isEqualTo(Market.TPEX);
		assertThat(created.getName()).isEqualTo("環球晶");
		verifyNoInteractions(marketResolver);
	}

	@Test
	void 行情快取沒有_問官方並把查到的收盤價寫入快取() {
		given(marketResolver.resolve(eq("6488"), any(Deadline.class))).willReturn(StockLookup.found(
				Market.TPEX, "環球晶", LocalDate.of(2026, 11, 24), new BigDecimal("460.50"), LocalDate.of(2026, 11, 23)));

		holdingService.create("6488", 1, new BigDecimal("450.00"));

		Quote quote = quoteRepository.findById("6488").orElseThrow();
		assertThat(quote.getMarket()).isEqualTo(Market.TPEX);
		assertThat(quote.getClosePrice()).isEqualByComparingTo("460.50");
		assertThat(quote.getCloseDate()).isEqualTo(LocalDate.of(2026, 11, 23));
		assertThat(quote.getLatestTradeDate()).isEqualTo(LocalDate.of(2026, 11, 24));
	}

	@Test
	void 行情快取沒有_官方又連不上_錯誤且持股和快取都不儲存() {
		given(marketResolver.resolve(eq("2330"), any(Deadline.class))).willThrow(new MarketUnavailableException());

		assertThatThrownBy(() -> holdingService.create("2330", 1, new BigDecimal("1000.00")))
				.isInstanceOf(MarketUnavailableException.class);
		assertThat(holdingRepository.count()).isZero();
		assertThat(quoteRepository.count()).isZero();
	}

	@Test
	// lookupStock 是 NOT_SUPPORTED：會暫停交易、用另一條連線讀快取，看不到測試交易裡還沒 commit 的資料。
	// 所以這個測試不包交易，讓快取資料真的寫進去，測完自己刪掉
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void 即時查詢_行情快取有這個代號_直接回傳不呼叫官方() {
		cacheQuote("6488", "環球晶", Market.TPEX);
		try {
			StockLookup result = holdingService.lookupStock(" 6488 ");

			assertThat(result.market()).isEqualTo(Market.TPEX);
			assertThat(result.name()).isEqualTo("環球晶");
			assertThat(result.closePrice()).isEqualByComparingTo("460.50");
			verifyNoInteractions(marketResolver);
		} finally {
			quoteRepository.deleteAll();
		}
	}

	@Test
	void 新增時存入官方查到的名稱和市場() {
		given(marketResolver.resolve(eq("6488"), any(Deadline.class))).willReturn(StockLookup.found(
				Market.TPEX, "環球晶", LocalDate.of(2026, 11, 23), new BigDecimal("460.50")));

		Holding created = holdingService.create("6488", 1, new BigDecimal("450.00"));

		Holding saved = holdingRepository.findById(created.getId()).orElseThrow();
		assertThat(saved.getMarket()).isEqualTo(Market.TPEX);
		assertThat(saved.getName()).isEqualTo("環球晶");
	}

	@Test
	void 交給官方查詢的是正規化後的代號() {
		holdingService.create(" 00631l ", 1, new BigDecimal("20.00"));

		verify(marketResolver).resolve(eq("00631L"), any(Deadline.class));
	}

	@Test
	void 上市上櫃都查不到_丟出錯誤且不儲存() {
		given(marketResolver.resolve(eq("9999"), any(Deadline.class))).willThrow(new StockCodeNotFoundException("9999"));

		assertThatThrownBy(() -> holdingService.create("9999", 1, new BigDecimal("10.00")))
				.isInstanceOf(StockCodeNotFoundException.class)
				.hasMessage("查無此股票代號：9999（上市、上櫃都找不到）");
		assertThat(holdingRepository.count()).isZero();
	}

	@Test
	void 官方連不上無法確認_丟出錯誤且不儲存() {
		given(marketResolver.resolve(eq("2330"), any(Deadline.class))).willThrow(new MarketUnavailableException());

		assertThatThrownBy(() -> holdingService.create("2330", 1, new BigDecimal("1000.00")))
				.isInstanceOf(MarketUnavailableException.class)
				.hasMessage("暫時無法連線官方網站確認代號，請稍後再試");
		assertThat(holdingRepository.count()).isZero();
	}

	// ===== F-01：等官方時不占資料庫交易；行情快取和持股分開寫入 =====

	@MockitoSpyBean
	private QuoteService quoteService;

	@Test
	void 呼叫官方時沒有進行中的資料庫交易() {
		AtomicReference<Boolean> transactionActive = new AtomicReference<>();
		// 在「呼叫 resolve 的那條執行緒」上檢查：交易綁在執行緒上，
		// 放在假 client 裡檢查沒有意義（那是查詢專用的另一條執行緒，本來就不會有交易）
		given(marketResolver.resolve(eq("2330"), any(Deadline.class))).willAnswer(invocation -> {
			transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
			return StockLookup.found(Market.TWSE, "台積電", LocalDate.of(2026, 11, 24), new BigDecimal("2030.00"));
		});

		holdingService.create("2330", 1, new BigDecimal("1000.00"));

		assertThat(transactionActive.get()).isFalse();
		assertThat(holdingRepository.existsByCode("2330")).isTrue();
	}

	@Test
	void 行情快取寫入失敗時_持股仍然新增成功() {
		willThrow(new DataAccessResourceFailureException("模擬行情快取寫入失敗"))
				.given(quoteService).upsert(anyCollection());

		Holding created = holdingService.create("2330", 1, new BigDecimal("1000.00"));

		assertThat(holdingRepository.findById(created.getId())).isPresent();
		assertThat(quoteRepository.count()).isZero();
	}

	@Test
	void 即時查詢_代號正規化後交給官方查詢_而且不會新增資料() {
		given(marketResolver.resolve("00631L")).willReturn(StockLookup.found(
				Market.TWSE, "元大台灣50正2", LocalDate.of(2026, 11, 23), new BigDecimal("20.00")));

		StockLookup result = holdingService.lookupStock(" 00631l ");

		assertThat(result.name()).isEqualTo("元大台灣50正2");
		verify(marketResolver).resolve("00631L");
		assertThat(holdingRepository.count()).isZero();
	}

	@Test
	void 代號重複時不會去查官方() {
		holdingService.create("2330", 1, new BigDecimal("1000.00"));

		assertThatThrownBy(() -> holdingService.create("2330", 2, new BigDecimal("900.00")))
				.isInstanceOf(DuplicateCodeException.class);
		// 只有第一次新增查過官方
		verify(marketResolver, times(1)).resolve(eq("2330"), any(Deadline.class));
	}

	@Test
	void 新增持股_時間用台北時區() {
		Holding created = holdingService.create("2330", 1, new BigDecimal("1000.00"));

		assertThat(created.getId()).isNotNull();
		assertThat(created.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 11, 24, 14, 0));
		assertThat(created.getUpdatedAt()).isEqualTo(created.getCreatedAt());
	}

	@Test
	void 新增時代號去掉前後空白並轉大寫() {
		Holding created = holdingService.create(" 00631l ", 1, new BigDecimal("20.00"));

		assertThat(created.getCode()).isEqualTo("00631L");
	}

	@Test
	void 重複新增同一代號_丟出錯誤且不儲存() {
		holdingService.create("2330", 1, new BigDecimal("1000.00"));

		assertThatThrownBy(() -> holdingService.create("2330", 5, new BigDecimal("900.00")))
				.isInstanceOf(DuplicateCodeException.class)
				.hasMessage("2330 已在持股中");
		assertThat(holdingRepository.count()).isEqualTo(1);
		// 原本那筆沒有被第二次新增改掉
		assertThat(holdingRepository.findAll().get(0).getLots()).isEqualTo(1);
	}

	@Test
	void 大小寫不同也算重複代號() {
		holdingService.create("00631L", 1, new BigDecimal("20.00"));

		assertThatThrownBy(() -> holdingService.create("00631l", 1, new BigDecimal("20.00")))
				.isInstanceOf(DuplicateCodeException.class)
				.hasMessage("00631L 已在持股中");
	}

	@Test
	void 修改只改張數和買進價() {
		Holding created = holdingService.create("2330", 1, new BigDecimal("1000.00"));

		holdingService.update(created.getId(), 2, new BigDecimal("980.50"));

		Holding updated = holdingService.findById(created.getId());
		assertThat(updated.getCode()).isEqualTo("2330");
		assertThat(updated.getLots()).isEqualTo(2);
		assertThat(updated.getBuyPrice()).isEqualByComparingTo("980.50");
	}

	@Test
	void 刪除後就查不到() {
		Holding created = holdingService.create("2330", 1, new BigDecimal("1000.00"));

		holdingService.delete(created.getId());

		assertThat(holdingRepository.existsById(created.getId())).isFalse();
	}

	@Test
	void 修改或刪除不存在的持股_丟出找不到() {
		assertThatThrownBy(() -> holdingService.update(999L, 1, BigDecimal.TEN))
				.isInstanceOf(HoldingNotFoundException.class);
		assertThatThrownBy(() -> holdingService.delete(999L))
				.isInstanceOf(HoldingNotFoundException.class);
	}
}
