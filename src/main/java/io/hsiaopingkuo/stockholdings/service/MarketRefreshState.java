package io.hsiaopingkuo.stockholdings.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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

	/** 補查收盤價時官方「暫時查不了」：同一檔一天最多試這麼多次，之後當天不再查（G-02）。 */
	public static final int MAX_CLOSE_BACKFILL_UNAVAILABLE_PER_DAY = 6;

	private final Market market;
	/** 哪一天已經拿到當天的資料（那天不用再抓整個市場）。 */
	private volatile LocalDate doneDate;
	/**
	 * G-02：哪一天還有持股的收盤價待補查。和 doneDate 分開記：
	 * 拿到當天的批次後不再重抓整個市場，但待補查的持股要繼續重試。每一輪補查完重新計算。
	 */
	private volatile LocalDate closeBackfillPendingDate;
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
	/** 同一天裡，每個代號補查時遇到「暫時查不了」的次數；和上面的代號一起在換日時清空。 */
	private final Map<String, Integer> closeBackfillUnavailableCounts = new HashMap<>();

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

	/** 這一天還有沒有持股的收盤價待補查。 */
	public boolean hasPendingCloseBackfill(LocalDate date) {
		return date.equals(closeBackfillPendingDate);
	}

	/** 補查完重新計算的結果：pending 是 true 代表還有待補查，排程要繼續跑。 */
	public void setPendingCloseBackfill(LocalDate date, boolean pending) {
		this.closeBackfillPendingDate = pending ? date : null;
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

	public synchronized void markCloseBackfilled(String code, LocalDate today) {
		resetIfNewDay(today);
		closeBackfilledCodes.add(code);
	}

	/**
	 * 今天還要不要補查這個代號：已經有結果（FOUND、NOT_FOUND），或「暫時查不了」已達上限，就不再查。
	 * 有上限才不會整天重試同一個一直查不到的代號。
	 */
	public synchronized boolean isCloseBackfillSettled(String code, LocalDate today) {
		resetIfNewDay(today);
		return closeBackfilledCodes.contains(code) || closeBackfillUnavailableCounts.getOrDefault(code,
				0) >= MAX_CLOSE_BACKFILL_UNAVAILABLE_PER_DAY;
	}

	/** 記一次「暫時查不了」，回傳今天累計幾次。 */
	public synchronized int recordCloseBackfillUnavailable(String code, LocalDate today) {
		resetIfNewDay(today);
		return closeBackfillUnavailableCounts.merge(code, 1, Integer::sum);
	}

	/** 只記今天：日期換了就清空，隔天每一檔都會再補查一次。 */
	private void resetIfNewDay(LocalDate today) {
		if (!today.equals(closeBackfillDate)) {
			closeBackfilledCodes.clear();
			closeBackfillUnavailableCounts.clear();
			closeBackfillDate = today;
		}
	}
}
