package io.hsiaopingkuo.stockholdings.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 冪等鍵的設定（application.properties 的 idempotency.*，第 6 題決定 6-A）。
 *
 * @param retention       紀錄保留多久；過期但還沒被排程刪掉的紀錄仍然有效
 * @param cleanupEnabled  清除排程的開關（測試時關掉，需要的測試直接呼叫 purgeExpired）
 * @param cleanupInterval 多久刪一次過期紀錄
 */
@ConfigurationProperties("idempotency")
public record IdempotencyProperties(Duration retention, boolean cleanupEnabled, Duration cleanupInterval) {
}
