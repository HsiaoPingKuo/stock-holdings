package io.hsiaopingkuo.stockholdings.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.hsiaopingkuo.stockholdings.service.IdempotencyCleanupScheduler;
import io.hsiaopingkuo.stockholdings.service.IdempotencyService;

/** 冪等鍵的設定，以及清除過期紀錄的排程（第 6 題決定 6-A）。 */
@Configuration
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyConfig {

	/**
	 * 清除排程只在 idempotency.cleanup-enabled=true 時建立；@EnableScheduling 放在這個內部類別上，
	 * 關掉時連排程功能都不啟用（目前其他背景工作都自己管執行緒，不依賴 @Scheduled）。
	 */
	@Configuration
	@EnableScheduling
	@ConditionalOnBooleanProperty("idempotency.cleanup-enabled")
	static class CleanupConfig {

		@Bean
		IdempotencyCleanupScheduler idempotencyCleanupScheduler(IdempotencyService idempotencyService) {
			return new IdempotencyCleanupScheduler(idempotencyService);
		}
	}
}
