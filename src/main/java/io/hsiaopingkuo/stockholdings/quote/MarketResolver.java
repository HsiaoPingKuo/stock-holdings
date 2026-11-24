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
	// 查詢用的執行緒；不用 CompletableFuture 預設的共用執行緒池，那是給計算用的，拿來等網路會卡住別人
	private final ExecutorService executor;
	// 代號 → 最近一次的確定結果。輸入時的即時提示和按下新增會查同一個代號，記住結果才不會重複打官方；
	// 用 ConcurrentHashMap：多個使用者的請求可能同時讀寫
	private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();
	// 代號 → 正在進行中的官方查詢（F-02）。查詢結束（成功或失敗）就移除，只存「這一刻還沒查完的」
	private final Map<String, CompletableFuture<StockLookup>> inFlight = new ConcurrentHashMap<>();

	public MarketResolver(StockLookupClient twse, StockLookupClient tpex, Clock clock, Duration lookupTimeout,
			Duration cacheTtl) {
		this.twse = twse;
		this.tpex = tpex;
		this.clock = clock;
		this.lookupTimeout = lookupTimeout;
		this.cacheTtl = cacheTtl;
		this.executor = newExecutor();
	}

	/**
	 * @param code 已正規化（trim、大寫）的代號
	 * @return 查到的那一邊的結果（含市場、名稱）
	 * @throws StockCodeNotFoundException 上市、上櫃都確定沒有這個代號
	 * @throws MarketUnavailableException 官方網站連不上或回應異常，無法確認
	 * @throws MarketAmbiguousException   兩個市場都查到且最近交易日相同，無法判斷
	 */
	public StockLookup resolve(String code) {
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
			startLookup(code, mine);
		}
		return await(code, shared);
	}

	/** 由第一個請求呼叫：真的去查官方，查完把結果交給所有在等的人。 */
	private void startLookup(String code, CompletableFuture<StockLookup> shared) {
		CompletableFuture<StockLookup> lookup;
		try {
			lookup = lookupBothMarkets(code);
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
	 * 每個請求各自等待共用的結果，最多等 lookupTimeout 再加一點緩衝。
	 * 共用的查詢本身最晚 lookupTimeout 就會結束（見 lookupBothMarkets），所以正常情況下一定等得到；
	 * 自己的上限是最後一道保險，確保不會因為共用別人的查詢而一直等。
	 */
	private StockLookup await(String code, CompletableFuture<StockLookup> shared) {
		try {
			return shared.get(lookupTimeout.plus(AWAIT_GRACE).toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			log.warn("等待 {} 的查詢結果超過 {}，視為無法確認", code, lookupTimeout.plus(AWAIT_GRACE));
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
	 * 不論官方多慢，這個 future 最晚 lookupTimeout（預設 8 秒）就會完成：時間到還沒回來的一方視為無法確認。
	 */
	private CompletableFuture<StockLookup> lookupBothMarkets(String code) {
		// 一定要用台北時區的 clock：主機在海外時，無參數的 now() 在月初可能還是上個月
		LocalDate today = LocalDate.now(clock);
		CompletableFuture<StockLookup> twseFuture =
				CompletableFuture.supplyAsync(() -> lookupRecentMonths(twse, code, today), executor);
		CompletableFuture<StockLookup> tpexFuture =
				CompletableFuture.supplyAsync(() -> lookupRecentMonths(tpex, code, today), executor);

		return CompletableFuture.allOf(twseFuture, tpexFuture)
				// 時間到就當作「已完成」往下走，不再等還沒回來的那一邊
				.completeOnTimeout(null, lookupTimeout.toMillis(), TimeUnit.MILLISECONDS)
				.thenApply(ignored -> {
					if (!twseFuture.isDone() || !tpexFuture.isDone()) {
						log.warn("查詢 {} 的市場超過 {}，還沒回來的一方視為無法確認", code, lookupTimeout);
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

	/** 已經完成就取結果；還沒完成（逾時）就取消並當成 UNAVAILABLE。 */
	private static StockLookup resultOf(CompletableFuture<StockLookup> future, Market market) {
		if (future.isDone() && !future.isCompletedExceptionally()) {
			return future.join();
		}
		future.cancel(true);
		return StockLookup.unavailable(market);
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

	private static ExecutorService newExecutor() {
		AtomicInteger counter = new AtomicInteger();
		return Executors.newFixedThreadPool(4, runnable -> {
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
