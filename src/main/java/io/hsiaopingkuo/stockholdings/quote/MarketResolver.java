package io.hsiaopingkuo.stockholdings.quote;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.exception.MarketAmbiguousException;
import io.hsiaopingkuo.stockholdings.exception.MarketUnavailableException;
import io.hsiaopingkuo.stockholdings.exception.StockCodeNotFoundException;
import io.hsiaopingkuo.stockholdings.quote.StockLookup.Status;

/**
 * 判斷一個代號是上市還是上櫃：同時查證交所和櫃買，再把兩邊結果合併成一個結論。
 *
 * 合併規則：
 * 只有證交所 FOUND → 上市；只有櫃買 FOUND → 上櫃；
 * 兩邊都 FOUND → 選最近交易日較新的一邊，日期相同 → 無法判斷（MarketAmbiguousException）；
 * 兩邊都 NOT_FOUND → 代號不存在；
 * 其他（至少一邊查不了、另一邊沒找到）→ 無法確認，請使用者稍後再試。
 *
 * 確定的結果（查到、確定查不到）會記住一段時間（預設 10 分鐘），期間內同一代號不再打官方。
 * 同一個代號同時有多個請求時，只送一次官方查詢，其他請求共用結果（F-02）。
 */
public class MarketResolver implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(MarketResolver.class);

	// 共用的查詢最晚 lookupTimeout 結束；等待的人多等一點點，讓「時間到時剛好回來的結果」來得及被採用
	private static final Duration AWAIT_GRACE = Duration.ofMillis(500);

	private final StockLookupClient twse;
	private final StockLookupClient tpex;
	private final Clock clock;
	private final Duration lookupTimeout;
	private final Duration cacheTtl;
	// F-01：一個請求的時間預算、同時查官方的上限、剩下多少時間以下就不查官方
	private final Duration requestBudget;
	private final int maxConcurrentLookups;
	private final Duration minRemaining;
	// 號碼牌：同時查官方的代號最多 maxConcurrentLookups 個。fair = true：等待的人照先來後到拿到名額
	private final Semaphore permits;
	// 查詢用的執行緒；不用 CompletableFuture 預設的共用執行緒池，那是給計算用的，拿來等網路會卡住別人
	private final ExecutorService executor;
	// 代號 → 最近一次的確定結果。輸入時的即時提示和按下新增會查同一個代號，記住結果才不會重複打官方；
	// 用 ConcurrentHashMap：多個使用者的請求可能同時讀寫
	private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();
	// 代號 → 正在進行中的官方查詢（F-02）。查詢結束（成功或失敗）就移除，只存「這一刻還沒查完的」
	private final Map<String, CompletableFuture<StockLookup>> inFlight = new ConcurrentHashMap<>();

	/**
	 * @param requestBudget        沒有指定截止時間時（resolve(code)），從呼叫那一刻起算的時間預算
	 * @param maxConcurrentLookups 同時查官方的代號數上限
	 * @param minRemaining         剩下的時間少於這個值就不查官方
	 */
	public MarketResolver(StockLookupClient twse, StockLookupClient tpex, Clock clock, Duration lookupTimeout,
			Duration cacheTtl, Duration requestBudget, int maxConcurrentLookups, Duration minRemaining) {
		if (maxConcurrentLookups < 1) {
			// 設成 0 會讓所有新增都等不到名額；啟動時就擋下，比上線後才發現好
			throw new IllegalArgumentException("quote.max-concurrent-lookups 至少要是 1：" + maxConcurrentLookups);
		}
		this.twse = twse;
		this.tpex = tpex;
		this.clock = clock;
		this.lookupTimeout = lookupTimeout;
		this.cacheTtl = cacheTtl;
		this.requestBudget = requestBudget;
		this.maxConcurrentLookups = maxConcurrentLookups;
		this.minRemaining = minRemaining;
		this.permits = new Semaphore(maxConcurrentLookups, true);
		// 每個代號同時查上市、上櫃，各占一條執行緒；拿到號碼牌的查詢就不會在執行緒池裡排隊
		this.executor = newExecutor(maxConcurrentLookups * 2);
	}

	/**
	 * 只想測合併規則、快取的單元測試用：預算 = 等待上限 + 緩衝（等同加上預算之前的行為）、同時 4 個、不保留最少時間。
	 * 正式環境由 QuoteConfig 用上面完整的建構子建立，三個值都來自設定檔。
	 */
	public MarketResolver(StockLookupClient twse, StockLookupClient tpex, Clock clock, Duration lookupTimeout,
			Duration cacheTtl) {
		this(twse, tpex, clock, lookupTimeout, cacheTtl, lookupTimeout.plus(AWAIT_GRACE), 4, Duration.ZERO);
	}

	/**
	 * 從現在起算 requestBudget 的版本：即時提示、背景補查舊持股用。
	 *
	 * @see #resolve(String, Deadline)
	 */
	public StockLookup resolve(String code) {
		return resolve(code, Deadline.after(requestBudget));
	}

	/**
	 * @param code     已正規化（trim、大寫）的代號
	 * @param deadline 這個請求的截止時間；排隊等名額、等官方都不會超過它
	 * @return 查到的那一邊的結果（含市場、名稱）
	 * @throws StockCodeNotFoundException 上市、上櫃都確定沒有這個代號
	 * @throws MarketUnavailableException 官方網站連不上、回應異常，或時間不夠、同時查詢的名額已滿，無法確認
	 * @throws MarketAmbiguousException   兩個市場都查到且最近交易日相同，無法判斷
	 */
	public StockLookup resolve(String code, Deadline deadline) {
		CachedResult cached = cache.get(code);
		if (cached != null && clock.instant().isBefore(cached.expiresAt())) {
			if (cached.found() == null) {
				throw new StockCodeNotFoundException(code);
			}
			return cached.found();
		}

		// F-02：同一個代號同時只送一次官方查詢。
		// putIfAbsent 是原子操作：多個請求同時到，只有一個會登記成功（由它負責查官方），其他人拿到同一個 future 等結果
		CompletableFuture<StockLookup> mine = new CompletableFuture<>();
		CompletableFuture<StockLookup> shared = inFlight.putIfAbsent(code, mine);
		if (shared == null) {
			shared = mine;
			startLookup(code, mine, deadline);
		}
		return await(code, shared, deadline);
	}

	/**
	 * 只查一個市場的最新資料：新增持股時，行情快取已經知道市場、但還沒有收盤價（批次那天剛好沒成交），
	 * 用逐檔 lookupLatest 往前找最近一個有成交日的收盤價（Qiao 檢查時發現）。
	 *
	 * 和 resolve 共用號碼牌、執行緒池和時間預算的算法（F-01）；
	 * 不做 10 分鐘快取和同代號合併（F-02）：同一個代號只能新增一次，不會有大量重複的查詢。
	 *
	 * @param code     已正規化（trim、大寫）的代號
	 * @param market   要查的市場（來自行情快取）
	 * @param deadline 這個請求的截止時間
	 * @return 不丟例外：名額已滿、時間不夠、逾時、官方失敗都回 UNAVAILABLE，被擋回 BLOCKED，由呼叫端決定怎麼處理
	 */
	public StockLookup lookupLatestInMarket(String code, Market market, Deadline deadline) {
		StockLookupClient client = market == Market.TWSE ? twse : tpex;
		if (!acquirePermit(code, deadline)) {
			return StockLookup.unavailable(market);
		}
		// 和 lookupWithPermit 相同：拿到號碼牌後，用剩下的時間決定最多等多久
		Duration limit = min(lookupTimeout, deadline.remaining().minus(AWAIT_GRACE));
		if (limit.isNegative() || limit.isZero()) {
			permits.release();
			log.warn("{} 拿到查詢名額時已經沒有時間查 {}，不補查收盤價", code, market);
			return StockLookup.unavailable(market);
		}
		CompletableFuture<StockLookup> future;
		try {
			// 一定要用台北時區的 clock：主機在海外時，無參數的 now() 在月初可能還是上個月
			LocalDate today = LocalDate.now(clock);
			future = CompletableFuture.supplyAsync(() -> lookupRecentMonths(client, code, today), executor);
		} catch (RuntimeException e) {
			// 查詢沒送出去（例如伺服器關閉中），號碼牌要還回去
			permits.release();
			log.warn("{} 查詢 {} 沒有送出", market, code, e);
			return StockLookup.unavailable(market);
		}
		// 號碼牌等請求真的結束才還，不是時間到就還（理由同 lookupBothMarkets）
		future.whenComplete((result, error) -> permits.release());
		try {
			return future.get(limit.toNanos(), TimeUnit.NANOSECONDS);
		} catch (TimeoutException e) {
			log.warn("{} 查詢 {} 超過 {}，視為暫時查不了", market, code, limit);
			return StockLookup.unavailable(market);
		} catch (InterruptedException e) {
			// 保留中斷狀態，讓上層知道這個執行緒被要求停止
			Thread.currentThread().interrupt();
			return StockLookup.unavailable(market);
		} catch (ExecutionException e) {
			// lookupRecentMonths 已經接住 client 的例外，照理不會到這裡；萬一到了也不讓新增失敗
			log.warn("{} 查詢 {} 時發生例外", market, code, e.getCause());
			return StockLookup.unavailable(market);
		}
	}

	/** 由第一個請求呼叫：拿號碼牌、真的去查官方，查完把結果交給所有在等的人。 */
	private void startLookup(String code, CompletableFuture<StockLookup> shared, Deadline deadline) {
		CompletableFuture<StockLookup> lookup;
		try {
			lookup = lookupWithPermit(code, deadline);
		} catch (RuntimeException e) {
			// 例如伺服器關閉中、執行緒池已停止，連查詢都送不出去；一樣要走下面的清除流程，不能卡在 inFlight
			lookup = CompletableFuture.failedFuture(e);
		}
		lookup.whenComplete((result, error) -> {
			Throwable cause = unwrap(error);
			if (error == null) {
				remember(code, result);
			} else if (cause instanceof StockCodeNotFoundException) {
				// 「確定查不到」也記住，使用者反覆輸入打錯的代號時不會一直打官方
				remember(code, null);
			}
			// MarketUnavailableException 不記：連不上可能只是暫時的，下次要重新查；
			// MarketAmbiguousException 也不記：無法判斷的結果一律不儲存，修好資料後才能立刻重查

			// 不論成功或失敗都要清掉「進行中」的紀錄，否則這個代號之後永遠拿到同一個舊結果。
			// 先清掉再通知：之後進來的請求會查 10 分鐘快取或重新查官方，不會再拿到這個已結束的 future。
			// remove(key, value)：只刪自己登記的那一筆，不會誤刪下一輪別人剛登記的
			inFlight.remove(code, shared);
			if (error == null) {
				shared.complete(result);
			} else {
				shared.completeExceptionally(cause);
			}
		});
	}

	/**
	 * 先拿號碼牌，再查官方。號碼牌只等到「剩下的時間 − minRemaining」為止：
	 * 排到的時候已經來不及查官方，不如馬上回「暫時無法確認」，不要讓使用者排到超時。
	 */
	private CompletableFuture<StockLookup> lookupWithPermit(String code, Deadline deadline) {
		if (!acquirePermit(code, deadline)) {
			return CompletableFuture.failedFuture(new MarketUnavailableException());
		}
		// 拿到號碼牌之後才算查官方能用多久：例如排隊 3 秒後只剩 6 秒，就只等 6 秒（不是固定 8 秒）。
		// 再扣掉 AWAIT_GRACE：查詢在截止前一點點結束，第一個請求才來得及在自己的預算內拿到結果
		Duration limit = min(lookupTimeout, deadline.remaining().minus(AWAIT_GRACE));
		if (limit.isNegative() || limit.isZero()) {
			permits.release();
			log.warn("{} 拿到查詢名額時已經沒有時間查官方，回覆暫時無法確認", code);
			return CompletableFuture.failedFuture(new MarketUnavailableException());
		}
		try {
			return lookupBothMarkets(code, limit);
		} catch (RuntimeException e) {
			// 查詢沒送出去（例如伺服器關閉中），號碼牌要還回去
			permits.release();
			throw e;
		}
	}

	private boolean acquirePermit(String code, Deadline deadline) {
		Duration maxWait = deadline.remaining().minus(minRemaining);
		if (maxWait.isNegative()) {
			log.warn("{} 剩下的時間不到 {}，不查官方，回覆暫時無法確認", code, minRemaining);
			return false;
		}
		try {
			if (permits.tryAcquire(maxWait.toNanos(), TimeUnit.NANOSECONDS)) {
				return true;
			}
			log.warn("同時查官方的代號已達上限 {} 個，{} 等了 {} 仍沒有名額，回覆暫時無法確認",
					maxConcurrentLookups, code, maxWait);
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/**
	 * 每個請求各自等待共用的結果，最多等「lookupTimeout + 緩衝」和「自己剩下的時間」比較短的那個。
	 * 第一個請求的截止時間決定了共用查詢最晚何時結束（見 lookupWithPermit），所以它一定等得到結果；
	 * 後來加入的請求如果剩下的時間比較少，就只等到自己的截止時間，不會因為共用別人的查詢而超時。
	 */
	private StockLookup await(String code, CompletableFuture<StockLookup> shared, Deadline deadline) {
		Duration wait = min(lookupTimeout.plus(AWAIT_GRACE), deadline.remaining());
		try {
			return shared.get(wait.toNanos(), TimeUnit.NANOSECONDS);
		} catch (TimeoutException e) {
			log.warn("等待 {} 的查詢結果超過 {}，視為無法確認", code, wait);
			throw new MarketUnavailableException();
		} catch (InterruptedException e) {
			// 保留中斷狀態，讓上層知道這個執行緒被要求停止
			Thread.currentThread().interrupt();
			throw new MarketUnavailableException();
		} catch (ExecutionException e) {
			// 每個請求丟出自己的新例外（而不是共用同一個例外物件），錯誤紀錄裡的呼叫位置才會是自己的
			Throwable cause = e.getCause();
			if (cause instanceof StockCodeNotFoundException) {
				throw new StockCodeNotFoundException(code);
			}
			if (cause instanceof MarketAmbiguousException) {
				throw new MarketAmbiguousException(code);
			}
			if (!(cause instanceof MarketUnavailableException)) {
				// 預期之外的錯誤：記下來方便追查，對使用者一律是「無法確認」，不讓頁面變成 500
				log.warn("查詢 {} 的市場時發生例外", code, cause);
			}
			throw new MarketUnavailableException();
		}
	}

	/** thenApply 裡丟出的例外會被包一層 CompletionException，取出真正的原因。 */
	private static Throwable unwrap(Throwable error) {
		if (error instanceof CompletionException && error.getCause() != null) {
			return error.getCause();
		}
		return error;
	}

	/** found 是 null 代表「確定查不到」。 */
	private void remember(String code, StockLookup found) {
		Instant now = clock.instant();
		// 順手清掉過期的項目，避免快取無限長大（持股頁的代號數量不多，整個掃一遍很快）
		cache.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
		cache.put(code, new CachedResult(found, now.plus(cacheTtl)));
	}

	private record CachedResult(StockLookup found, Instant expiresAt) {
	}

	/**
	 * 同時查兩個市場，回傳「合併後的結果」的 future（不在這裡等）。
	 * 不論官方多慢，這個 future 最晚 limit 就會完成：時間到還沒回來的一方視為無法確認。
	 * 呼叫前要先拿到號碼牌；號碼牌在兩邊的請求都真的結束後才歸還。
	 */
	private CompletableFuture<StockLookup> lookupBothMarkets(String code, Duration limit) {
		// 一定要用台北時區的 clock：主機在海外時，無參數的 now() 在月初可能還是上個月
		LocalDate today = LocalDate.now(clock);
		CompletableFuture<StockLookup> twseFuture =
				CompletableFuture.supplyAsync(() -> lookupRecentMonths(twse, code, today), executor);
		CompletableFuture<StockLookup> tpexFuture =
				CompletableFuture.supplyAsync(() -> lookupRecentMonths(tpex, code, today), executor);

		// 號碼牌等兩邊的 HTTP 請求「真的結束」才還，不是時間到就還：
		// 時間到只代表不再等，請求仍在背景跑到 read timeout、繼續占著執行緒；提早還號碼牌，新的查詢就會在執行緒池裡排隊
		CompletableFuture.allOf(twseFuture, tpexFuture).whenComplete((ignored, error) -> permits.release());

		// 注意這裡是另一個 allOf：completeOnTimeout 會讓它提早完成，不能和上面還號碼牌的那個共用
		return CompletableFuture.allOf(twseFuture, tpexFuture)
				// 時間到就當作「已完成」往下走，不再等還沒回來的那一邊
				.completeOnTimeout(null, limit.toNanos(), TimeUnit.NANOSECONDS)
				.thenApply(ignored -> {
					if (!twseFuture.isDone() || !tpexFuture.isDone()) {
						log.warn("查詢 {} 的市場超過 {}，還沒回來的一方視為無法確認", code, limit);
					}
					return decide(code, resultOf(twseFuture, Market.TWSE), resultOf(tpexFuture, Market.TPEX));
				});
	}

	/**
	 * 這個月查不到（或整月沒成交）時再查上個月，規則在 StockLookupClient.lookupLatest。
	 * 月初陷阱：證交所對「代號不存在」和「這個月還沒有交易日」回的是同一句話，
	 * 只查這個月的話，每月 1 號（或連假後的第一個交易日前）所有股票都會被當成不存在。
	 */
	private static StockLookup lookupRecentMonths(StockLookupClient client, String code, LocalDate today) {
		try {
			return client.lookupLatest(code, today);
		} catch (RuntimeException e) {
			// client 約定不丟例外；萬一丟了，也只算這一邊查不了，不讓整個新增流程變成 500
			log.warn("{} 查詢 {} 時發生例外", client.market(), code, e);
			return StockLookup.unavailable(client.market());
		}
	}

	/**
	 * 已經完成就取結果；還沒完成（逾時）就當成 UNAVAILABLE。
	 * 不呼叫 cancel：CompletableFuture.cancel 不會中斷正在等網路的執行緒，HTTP 請求照樣跑到 read timeout，
	 * 反而會讓「還號碼牌」的 allOf 提早完成，號碼牌提早歸還。
	 */
	private static StockLookup resultOf(CompletableFuture<StockLookup> future, Market market) {
		if (future.isDone() && !future.isCompletedExceptionally()) {
			return future.join();
		}
		return StockLookup.unavailable(market);
	}

	private static Duration min(Duration a, Duration b) {
		return a.compareTo(b) <= 0 ? a : b;
	}

	static StockLookup decide(String code, StockLookup twseResult, StockLookup tpexResult) {
		boolean twseFound = twseResult.status() == Status.FOUND;
		boolean tpexFound = tpexResult.status() == Status.FOUND;
		if (twseFound && tpexFound) {
			return newerOf(code, twseResult, tpexResult);
		}
		if (twseFound) {
			return twseResult;
		}
		if (tpexFound) {
			return tpexResult;
		}
		if (twseResult.status() == Status.NOT_FOUND && tpexResult.status() == Status.NOT_FOUND) {
			throw new StockCodeNotFoundException(code);
		}
		// 至少一邊查不了：不能斷定代號不存在（可能正好在查不了的那個市場）
		throw new MarketUnavailableException();
	}

	/**
	 * 兩邊都查到時，選「最近交易日」較新的那一邊。
	 * 會發生的原因：查詢這個月沒資料時會回查上個月，可能拿到「已轉上櫃（或上市）前留下的舊資料」，
	 * 這時日期較新的那一邊才是目前的市場。日期相同就無法判斷，不猜，交給人工追查。
	 */
	private static StockLookup newerOf(String code, StockLookup twseResult, StockLookup tpexResult) {
		LocalDate twseDate = twseResult.tradeDate();
		LocalDate tpexDate = tpexResult.tradeDate();
		if (twseDate == null || tpexDate == null || twseDate.isEqual(tpexDate)) {
			log.warn("{} 兩個市場都查到，無法判斷：{} 最近交易日 {}，{} 最近交易日 {}；不儲存",
					code, twseResult.market(), twseDate, tpexResult.market(), tpexDate);
			throw new MarketAmbiguousException(code);
		}
		StockLookup newer = twseDate.isAfter(tpexDate) ? twseResult : tpexResult;
		log.info("{} 兩個市場都查到：{} 最近交易日 {}，{} 最近交易日 {}；選日期較新的 {}",
				code, twseResult.market(), twseDate, tpexResult.market(), tpexDate, newer.market());
		return newer;
	}

	private static ExecutorService newExecutor(int threads) {
		AtomicInteger counter = new AtomicInteger();
		return Executors.newFixedThreadPool(threads, runnable -> {
			Thread thread = new Thread(runnable, "quote-lookup-" + counter.incrementAndGet());
			// daemon：就算有查詢卡住，也不會擋住程式結束
			thread.setDaemon(true);
			return thread;
		});
	}

	/** Spring 關閉時自動呼叫（@Bean 會自動偵測 close 方法）。 */
	@Override
	public void close() {
		executor.shutdownNow();
	}
}
