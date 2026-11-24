package io.hsiaopingkuo.stockholdings.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import io.hsiaopingkuo.stockholdings.quote.HolidayCalendar;
import io.hsiaopingkuo.stockholdings.quote.MarketResolver;
import io.hsiaopingkuo.stockholdings.quote.TwseHolidayClient;
import io.hsiaopingkuo.stockholdings.quote.TpexOtcClient;
import io.hsiaopingkuo.stockholdings.quote.TpexTradingStockClient;
import io.hsiaopingkuo.stockholdings.quote.TwseMiIndexClient;
import io.hsiaopingkuo.stockholdings.quote.TwseStockDayClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 組裝外部行情相關的物件：兩個市場的逐檔 client、批次 client，以及合併兩邊結果的 MarketResolver。
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
