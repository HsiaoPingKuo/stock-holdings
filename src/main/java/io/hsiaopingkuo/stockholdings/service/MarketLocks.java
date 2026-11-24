package io.hsiaopingkuo.stockholdings.service;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

import io.hsiaopingkuo.stockholdings.entity.Market;

/**
 * 每個市場一把鎖（第 6 題第 4c 階段）：行情排程和除權息同步共用，
 * 同一個市場同時只有一邊在對官方發請求，請求間隔和被擋的判斷才不會交錯；同一個市場的除權息也不會同時跑兩輪。
 *
 * 用只有 1 個名額的 Semaphore，而不是 ReentrantLock：ReentrantLock 允許同一條執行緒重複拿，
 * Semaphore 不管哪條執行緒都只能拿一次，規則比較單純，也能在單一執行緒的測試裡驗證「拿不到」。
 * 拿到的一方一定要在 finally 裡 release。
 */
public class MarketLocks {

	private final Map<Market, Semaphore> locks = new EnumMap<>(Market.class);

	public MarketLocks() {
		for (Market market : Market.values()) {
			locks.put(market, new Semaphore(1));
		}
	}

	/** 等到拿到為止（行情用：一輪的時間很重要，寧可等除權息做完）。 */
	public void acquire(Market market) throws InterruptedException {
		locks.get(market).acquire();
	}

	/** 拿得到就拿，拿不到馬上回 false，不等（除權息用：延後或跳過就好）。 */
	public boolean tryAcquire(Market market) {
		return locks.get(market).tryAcquire();
	}

	public void release(Market market) {
		locks.get(market).release();
	}
}
