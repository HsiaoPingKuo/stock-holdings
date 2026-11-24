package io.hsiaopingkuo.stockholdings.service;

import java.time.Instant;
import java.time.LocalDate;

import io.hsiaopingkuo.stockholdings.entity.Market;

/**
 * 單一市場的更新狀態。上市、上櫃各有一份，彼此互不影響：
 * 證交所被擋在冷卻時，櫃買照常查；上市拿到今天的資料停止了，上櫃仍繼續。
 *
 * 欄位用 volatile：同一個市場的每一輪可能由不同的排程執行緒執行，
 * 另一個市場的補查也會來讀冷卻狀態，volatile 保證讀到的是最新寫入的值。
 */
public class MarketRefreshState {

	private final Market market;
	/** 哪一天已經拿到當天的資料（那天不用再查）。 */
	private volatile LocalDate doneDate;
	/** 上一輪批次失敗、改用逐檔：下一輪間隔要拉長。 */
	private volatile boolean usedPerStock;
	/** 被擋時，冷卻到什麼時候；null 代表沒有冷卻。 */
	private volatile Instant cooldownUntil;

	public MarketRefreshState(Market market) {
		this.market = market;
	}

	public Market market() {
		return market;
	}

	public boolean isDoneFor(LocalDate date) {
		return date.equals(doneDate);
	}

	public void markDone(LocalDate date) {
		this.doneDate = date;
	}

	public boolean usedPerStock() {
		return usedPerStock;
	}

	public void setUsedPerStock(boolean usedPerStock) {
		this.usedPerStock = usedPerStock;
	}

	public boolean isCoolingDown(Instant now) {
		Instant until = cooldownUntil;
		return until != null && now.isBefore(until);
	}

	public Instant cooldownUntil() {
		return cooldownUntil;
	}

	public void startCooldown(Instant until) {
		this.cooldownUntil = until;
	}
}
