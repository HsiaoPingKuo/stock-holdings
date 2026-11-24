package io.hsiaopingkuo.stockholdings.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

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
	/**
	 * 補查收盤價的紀錄：哪一天、那天已經補查過（查到 FOUND 或 NOT_FOUND）的代號。
	 * 真的長期沒成交的股票（剛上市、停牌）補查完仍然沒有收盤價，記下來才不會每一輪都重查。
	 * 日期和代號要一起改，用 synchronized 的方法存取（不用 volatile）；只存在記憶體，重新啟動後會再補查一次。
	 */
	private LocalDate closeBackfillDate;
	private final Set<String> closeBackfilledCodes = new HashSet<>();

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

	/** 這個代號今天是不是已經補查過收盤價。 */
	public synchronized boolean isCloseBackfilled(String code, LocalDate today) {
		resetIfNewDay(today);
		return closeBackfilledCodes.contains(code);
	}

	public synchronized void markCloseBackfilled(String code, LocalDate today) {
		resetIfNewDay(today);
		closeBackfilledCodes.add(code);
	}

	/** 只記今天：日期換了就清空，隔天每一檔都會再補查一次。 */
	private void resetIfNewDay(LocalDate today) {
		if (!today.equals(closeBackfillDate)) {
			closeBackfilledCodes.clear();
			closeBackfillDate = today;
		}
	}
}
