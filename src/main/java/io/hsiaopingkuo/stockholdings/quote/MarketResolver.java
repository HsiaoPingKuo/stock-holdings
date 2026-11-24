package io.hsiaopingkuo.stockholdings.quote;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
import io.hsiaopingkuo.stockholdings.exception.MarketUnavailableException;
import io.hsiaopingkuo.stockholdings.exception.StockCodeNotFoundException;
import io.hsiaopingkuo.stockholdings.quote.StockLookup.Status;

/**
 * 判斷一個代號是上市還是上櫃：同時查證交所和櫃買，再把兩邊結果合併成一個結論。
 *
 * 合併規則：
 * 證交所 FOUND → 上市；櫃買 FOUND → 上櫃；兩邊都 NOT_FOUND → 代號不存在；
 * 其他（至少一邊查不了、另一邊沒找到）→ 無法確認，請使用者稍後再試。
 *
 * 確定的結果（查到、確定查不到）會記住一段時間（預設 10 分鐘），期間內同一代號不再打官方。
 */
public class MarketResolver implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(MarketResolver.class);

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
	 */
	public StockLookup resolve(String code) {
		CachedResult cached = cache.get(code);
		if (cached != null && clock.instant().isBefore(cached.expiresAt())) {
			if (cached.found() == null) {
				throw new StockCodeNotFoundException(code);
			}
			return cached.found();
		}

		try {
			StockLookup found = lookupBothMarkets(code);
			remember(code, found);
			return found;
		} catch (StockCodeNotFoundException e) {
			// 「確定查不到」也記住，使用者反覆輸入打錯的代號時不會一直打官方
			remember(code, null);
			throw e;
		}
		// MarketUnavailableException 不記：連不上可能只是暫時的，下次要重新查
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

	private StockLookup lookupBothMarkets(String code) {
		// 一定要用台北時區的 clock：主機在海外時，無參數的 now() 在月初可能還是上個月
		LocalDate today = LocalDate.now(clock);
		CompletableFuture<StockLookup> twseFuture =
				CompletableFuture.supplyAsync(() -> lookupRecentMonths(twse, code, today), executor);
		CompletableFuture<StockLookup> tpexFuture =
				CompletableFuture.supplyAsync(() -> lookupRecentMonths(tpex, code, today), executor);

		try {
			// 整體只等 lookupTimeout（預設 8 秒），確保使用者按下新增後 10 秒內一定看到結果
			CompletableFuture.allOf(twseFuture, tpexFuture).get(lookupTimeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			log.warn("查詢 {} 的市場超過 {}，還沒回來的一方視為無法確認", code, lookupTimeout);
		} catch (InterruptedException e) {
			// 保留中斷狀態，讓上層知道這個執行緒被要求停止
			Thread.currentThread().interrupt();
		} catch (ExecutionException e) {
			// lookupRecentMonths 已經把例外轉成 UNAVAILABLE，理論上不會到這裡；下面 resultOf 會再保護一次
			log.warn("查詢 {} 的市場時發生例外", code, e);
		}

		return decide(code, resultOf(twseFuture, Market.TWSE), resultOf(tpexFuture, Market.TPEX));
	}

	/**
	 * 先查這個月；這個月「查不到」時再查上個月。
	 * 月初陷阱：證交所對「代號不存在」和「這個月還沒有交易日」回的是同一句話，
	 * 只查這個月的話，每月 1 號（或連假後的第一個交易日前）所有股票都會被當成不存在。
	 */
	private static StockLookup lookupRecentMonths(StockLookupClient client, String code, LocalDate today) {
		try {
			StockLookup thisMonth = client.lookupMonth(code, today);
			if (thisMonth.status() != Status.NOT_FOUND) {
				return thisMonth;
			}
			return client.lookupMonth(code, today.minusMonths(1));
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
		if (twseResult.status() == Status.FOUND) {
			if (tpexResult.status() == Status.FOUND) {
				// 同一代號不會同時上市又上櫃；真的發生時以證交所為準，留下紀錄方便追查
				log.warn("{} 在證交所和櫃買都查到，以證交所（上市）為準", code);
			}
			return twseResult;
		}
		if (tpexResult.status() == Status.FOUND) {
			return tpexResult;
		}
		if (twseResult.status() == Status.NOT_FOUND && tpexResult.status() == Status.NOT_FOUND) {
			throw new StockCodeNotFoundException(code);
		}
		// 至少一邊查不了：不能斷定代號不存在（可能正好在查不了的那個市場）
		throw new MarketUnavailableException();
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
