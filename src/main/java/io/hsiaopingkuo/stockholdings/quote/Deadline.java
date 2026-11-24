package io.hsiaopingkuo.stockholdings.quote;

import java.time.Duration;

/**
 * 一個請求的截止時間（F-01）：從收到請求開始算，之後每一步都用「還剩多少時間」決定要等多久。
 *
 * 用 System.nanoTime() 而不是 Clock：這裡只量「經過了多久」，和日期、時區無關；
 * nanoTime 只會往前走，主機校時（系統時間被往回調）也不會讓剩餘時間算錯。
 *
 * @param nanoTime 截止那一刻的 System.nanoTime() 值
 */
public record Deadline(long nanoTime) {

	public static Deadline after(Duration budget) {
		return new Deadline(System.nanoTime() + budget.toNanos());
	}

	/** 還剩多少時間；已經過了截止時間就回 0，不回負數。 */
	public Duration remaining() {
		// 用相減判斷先後，而不是直接比大小：nanoTime 的值可能是負數，相減才不會在數值溢位時出錯
		long left = nanoTime - System.nanoTime();
		return left > 0 ? Duration.ofNanos(left) : Duration.ZERO;
	}
}
