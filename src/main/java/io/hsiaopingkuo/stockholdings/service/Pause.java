package io.hsiaopingkuo.stockholdings.service;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * 「等一下再送下一個請求」。包成 interface：正式執行時真的 sleep，測試時換成只記錄、不等待，
 * 測試才不用真的等好幾秒。
 */
@FunctionalInterface
public interface Pause {

	/** @throws InterruptedException 伺服器關閉時執行緒被中斷，呼叫端要停止這一輪 */
	void sleep(Duration duration) throws InterruptedException;

	/** 在 [min, max) 之間隨機挑一個時間（毫秒為單位，所以不會剛好是整數秒）。 */
	static Duration randomBetween(Duration min, Duration max, RandomGenerator random) {
		long minMillis = min.toMillis();
		long maxMillis = max.toMillis();
		if (maxMillis <= minMillis) {
			return min;
		}
		return Duration.ofMillis(random.nextLong(minMillis, maxMillis));
	}

	/** 正式執行用：真的讓執行緒睡一段時間。 */
	static Pause threadSleep() {
		return duration -> Thread.sleep(duration.toMillis());
	}
}
