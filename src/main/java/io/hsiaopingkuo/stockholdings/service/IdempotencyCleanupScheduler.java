package io.hsiaopingkuo.stockholdings.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 每小時刪除超過 24 小時的冪等鍵紀錄（第 6 題決定 6-A）。
 * 只在 idempotency.cleanup-enabled=true 時由 IdempotencyConfig 建立；測試時關掉。
 */
public class IdempotencyCleanupScheduler {

	private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupScheduler.class);

	private final IdempotencyService idempotencyService;

	public IdempotencyCleanupScheduler(IdempotencyService idempotencyService) {
		this.idempotencyService = idempotencyService;
	}

	// 間隔從設定檔讀；第一次也等一個間隔再跑，啟動時不和行情、除權息的啟動輪搶資料庫
	@Scheduled(fixedDelayString = "${idempotency.cleanup-interval}",
			initialDelayString = "${idempotency.cleanup-interval}")
	public void purge() {
		try {
			int deleted = idempotencyService.purgeExpired();
			log.info("刪除過期的冪等鍵紀錄 {} 筆", deleted);
		} catch (RuntimeException e) {
			// 刪不掉只是表多留幾筆，下一輪再試；不讓例外中斷排程
			log.warn("刪除過期的冪等鍵紀錄失敗，下一輪再試", e);
		}
	}
}
