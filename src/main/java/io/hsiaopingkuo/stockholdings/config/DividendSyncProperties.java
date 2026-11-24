package io.hsiaopingkuo.stockholdings.config;

import java.time.Duration;
import java.time.LocalTime;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 除權息同步排程的設定（application.properties 的 dividend.sync.*，第 6 題）。
 * 時間一律是台北時間；Spring 會自動對應環境變數，例如 DIVIDEND_SYNC_ENABLED=false 可以關掉。
 * 被擋後冷卻多久不在這裡設：和行情共用 quote.refresh.cooldown，兩邊的冷卻才會一致。
 *
 * @param enabled               排程開關（測試時關掉，避免跑測試時發出請求）
 * @param dailyTime             每個交易日同步一次的時間
 * @param backfillCheckInterval 多久檢查一次「有沒有年份還沒有紀錄」（檢查只讀資料庫，有缺才發請求）
 * @param retryDelay            啟動輪、每日輪拿不到市場鎖（另一邊正在跑）時，延後多久再試
 */
@ConfigurationProperties("dividend.sync")
public record DividendSyncProperties(boolean enabled, LocalTime dailyTime, Duration backfillCheckInterval,
		Duration retryDelay) {
}
