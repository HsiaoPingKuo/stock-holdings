package io.hsiaopingkuo.stockholdings.config;

import java.time.Clock;
import java.util.Map;
import java.util.Random;
import java.util.random.RandomGenerator;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestClient;

import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.quote.HolidayCalendar;
import io.hsiaopingkuo.stockholdings.quote.MarketCloseBatchClient;
import io.hsiaopingkuo.stockholdings.quote.MarketResolver;
import io.hsiaopingkuo.stockholdings.quote.StockLookupClient;
import io.hsiaopingkuo.stockholdings.quote.TwseHolidayClient;
import io.hsiaopingkuo.stockholdings.quote.TpexOtcClient;
import io.hsiaopingkuo.stockholdings.quote.TpexTradingStockClient;
import io.hsiaopingkuo.stockholdings.quote.TwseMiIndexClient;
import io.hsiaopingkuo.stockholdings.quote.TwseStockDayClient;
import io.hsiaopingkuo.stockholdings.repository.HoldingRepository;
import io.hsiaopingkuo.stockholdings.repository.QuoteRepository;
import io.hsiaopingkuo.stockholdings.service.HoldingBackfillService;
import io.hsiaopingkuo.stockholdings.service.Pause;
import io.hsiaopingkuo.stockholdings.service.QuoteRefreshScheduler;
import io.hsiaopingkuo.stockholdings.service.QuoteRefreshService;
import io.hsiaopingkuo.stockholdings.service.RefreshSchedule;
import io.hsiaopingkuo.stockholdings.service.QuoteService;
import tools.jackson.databind.json.JsonMapper;

/**
 * 組裝外部行情相關的物件：兩個市場的逐檔 client、批次 client、合併兩邊結果的 MarketResolver，
 * 以及背景更新行情的 QuoteRefreshService。
 * 在這裡統一用 @Bean 建立（而不是各自加 @Component），主機網址、逾時從哪裡來一眼就看得到。
 */
@Configuration
@EnableConfigurationProperties(QuoteProperties.class)
public class QuoteConfig {

	@Bean
	TwseStockDayClient twseStockDayClient(QuoteProperties properties, JsonMapper jsonMapper) {
		return new TwseStockDayClient(restClient(properties.twse().baseUrl(), properties), jsonMapper);
	}

	@Bean
	TpexTradingStockClient tpexTradingStockClient(QuoteProperties properties, JsonMapper jsonMapper) {
		return new TpexTradingStockClient(restClient(properties.tpex().baseUrl(), properties), jsonMapper);
	}

	// 批次 client 和逐檔 client 用同一個主機設定：主考官換掉 TWSE_BASE_URL／TPEX_BASE_URL 時兩種都跟著換
	@Bean
	TwseMiIndexClient twseMiIndexClient(QuoteProperties properties, JsonMapper jsonMapper) {
		return new TwseMiIndexClient(restClient(properties.twse().baseUrl(), properties), jsonMapper);
	}

	@Bean
	TpexOtcClient tpexOtcClient(QuoteProperties properties, JsonMapper jsonMapper) {
		return new TpexOtcClient(restClient(properties.tpex().baseUrl(), properties), jsonMapper);
	}

	// 休市日表的主機和行情不同，用自己的網址設定（TWSE_HOLIDAY_URL）
	@Bean
	TwseHolidayClient twseHolidayClient(QuoteProperties properties, JsonMapper jsonMapper) {
		return new TwseHolidayClient(restClient(properties.holidayUrl(), properties), jsonMapper);
	}

	@Bean
	HolidayCalendar holidayCalendar(TwseHolidayClient twseHolidayClient, Clock clock) {
		return new HolidayCalendar(twseHolidayClient, clock);
	}

	// MarketResolver 有 close()，容器關閉時 Spring 會自動呼叫，把查詢用的執行緒收掉
	@Bean
	MarketResolver marketResolver(TwseStockDayClient twse, TpexTradingStockClient tpex, Clock clock,
			QuoteProperties properties) {
		return new MarketResolver(twse, tpex, clock, properties.lookupTimeout(), properties.lookupCacheTtl());
	}

	// ===== 背景更新行情 =====

	// 請求之間的等待；包成 bean，測試時才能換成不用真的等的版本
	@Bean
	Pause quoteRequestPause() {
		return Pause.threadSleep();
	}

	// 隨機間隔用。java.util.Random 可以多個執行緒共用（上市、上櫃兩個排程同時在跑）
	@Bean
	RandomGenerator quoteRandom() {
		return new Random();
	}

	// 哪個市場用哪個 client 在這裡寫清楚：上市 = 證交所，上櫃 = 櫃買
	@Bean
	QuoteRefreshService quoteRefreshService(TwseMiIndexClient twseBatch, TpexOtcClient tpexBatch,
			TwseStockDayClient twseStock, TpexTradingStockClient tpexStock, QuoteService quoteService,
			QuoteRepository quoteRepository, HoldingRepository holdingRepository,
			HoldingBackfillService backfillService, Pause pause, RandomGenerator random, QuoteProperties properties,
			Clock clock) {
		Map<Market, MarketCloseBatchClient> batchClients = Map.of(Market.TWSE, twseBatch, Market.TPEX, tpexBatch);
		Map<Market, StockLookupClient> stockClients = Map.of(Market.TWSE, twseStock, Market.TPEX, tpexStock);
		return new QuoteRefreshService(batchClients, stockClients, quoteService, quoteRepository, holdingRepository,
				backfillService, pause, random, properties.refresh(), clock);
	}

	/**
	 * 排程用自己的執行緒池（2 條，上市、上櫃各一條）：證交所在逐檔查詢之間等待時，不會卡住櫃買。
	 * 這個執行緒池只給行情排程用，不註冊成 bean，避免影響 Spring Boot 其他地方預設的執行緒池；
	 * 伺服器關閉時由 QuoteRefreshScheduler.close() 停止。
	 */
	@Bean
	QuoteRefreshScheduler quoteRefreshScheduler(QuoteRefreshService refreshService, HolidayCalendar holidayCalendar,
			RandomGenerator random, QuoteProperties properties, Clock clock) {
		ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
		taskScheduler.setPoolSize(2);
		taskScheduler.setThreadNamePrefix("quote-refresh-");
		// daemon：萬一有請求卡住，也不會擋住程式結束
		taskScheduler.setDaemon(true);
		taskScheduler.initialize();
		return new QuoteRefreshScheduler(refreshService, holidayCalendar,
				new RefreshSchedule(properties.refresh(), random), taskScheduler, clock, properties.refresh().enabled());
	}

	private static RestClient restClient(String baseUrl, QuoteProperties properties) {
		// JDK 內建的 HTTP 連線就夠用，不用另外加套件；一定要設逾時，否則官方卡住時會無限等待
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.connectTimeout());
		requestFactory.setReadTimeout(properties.readTimeout());
		return RestClient.builder()
				.baseUrl(baseUrl)
				.requestFactory(requestFactory)
				.build();
	}
}
