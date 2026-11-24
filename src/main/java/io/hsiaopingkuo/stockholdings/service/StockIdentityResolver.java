package io.hsiaopingkuo.stockholdings.service;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.entity.Quote;
import io.hsiaopingkuo.stockholdings.exception.MarketAmbiguousException;
import io.hsiaopingkuo.stockholdings.exception.MarketUnavailableException;
import io.hsiaopingkuo.stockholdings.exception.StockCodeNotFoundException;
import io.hsiaopingkuo.stockholdings.quote.Deadline;
import io.hsiaopingkuo.stockholdings.quote.MarketResolver;
import io.hsiaopingkuo.stockholdings.quote.StockLookup;

/**
 * 新增全新代號時，判斷它的市場和名稱（第 3 題的流程，第 6 題從 HoldingService.create 抽出來，
 * 讓 App 1.0 的新增和 2.0 的新增交易共用同一套，不會各寫一份而對不起來）。
 *
 * 注意：這裡可能呼叫官方 API（最多等到 deadline），呼叫端一定要在「沒有交易、沒有鎖」的時候呼叫（F-01）。
 * 這個類別自己不開交易；查、寫行情快取由 QuoteService 各自開很短的交易。
 */
@Component
public class StockIdentityResolver {

	private static final Logger log = LoggerFactory.getLogger(StockIdentityResolver.class);

	private final QuoteService quoteService;
	private final MarketResolver marketResolver;

	public StockIdentityResolver(QuoteService quoteService, MarketResolver marketResolver) {
		this.quoteService = quoteService;
		this.marketResolver = marketResolver;
	}

	/** 代號所屬的市場和名稱。 */
	public record StockIdentity(Market market, String name) {
	}

	/**
	 * 先查行情快取：排程每天會寫入全市場的行情，大部分代號在這裡就知道市場和名稱，不必打官方；
	 * 快取沒有收盤價時，才只對那個市場補查收盤價。快取沒有這個代號才問官方。
	 *
	 * @param code 已正規化的代號
	 * @throws StockCodeNotFoundException 快取沒有，上市、上櫃也都查不到這個代號
	 * @throws MarketUnavailableException 快取沒有，官方網站又連不上，無法確認代號
	 * @throws MarketAmbiguousException   快取沒有，兩個市場都查到且最近交易日相同，無法判斷（F-03）
	 */
	public StockIdentity resolve(String code, Deadline deadline) {
		Optional<Quote> cached = quoteService.findByCode(code);
		if (cached.isPresent()) {
			if (cached.get().getClosePrice() == null) {
				backfillCloseQuietly(code, cached.get().getMarket(), deadline);
			}
			return new StockIdentity(cached.get().getMarket(), cached.get().getName());
		}
		// 查不到或無法確認時直接丟例外，呼叫端後面的儲存不會執行
		StockLookup lookup = marketResolver.resolve(code, deadline);
		saveQuoteQuietly(code, lookup);
		return new StockIdentity(lookup.market(), lookup.name());
	}

	/**
	 * 快取有這一列、但沒有收盤價（例如批次那天剛好沒成交，資料庫又是全新的，Qiao 檢查時發現）：
	 * 市場已經知道，只查那個市場的逐檔資料，往前找最近一個有成交日的收盤價。
	 * 時間預算和同時查詢上限照 F-01（由 MarketResolver 負責）。
	 * 補查失敗、逾時或被擋都不影響新增：持股照常存，收盤價留給背景排程補。
	 */
	private void backfillCloseQuietly(String code, Market market, Deadline deadline) {
		StockLookup lookup = marketResolver.lookupLatestInMarket(code, market, deadline);
		if (lookup.status() == StockLookup.Status.FOUND) {
			saveQuoteQuietly(code, lookup);
		} else {
			log.warn("新增 {} 時補查收盤價沒有結果（{}），持股照常新增，收盤價由背景排程補上", code, lookup.status());
		}
	}

	/**
	 * 官方已經回了最近交易日和收盤價，順手寫進快取：新增後清單馬上看得到價格。
	 * 用 QuoteService 自己的短交易，和寫入持股分開：
	 * 行情是官方公開資料，不論持股有沒有存成功都是正確的；反過來，快取寫入失敗也不該讓使用者的新增失敗，
	 * 缺的行情由背景排程補上。
	 */
	private void saveQuoteQuietly(String code, StockLookup lookup) {
		try {
			quoteService.upsert(List.of(lookup.toDailyQuote(code)));
		} catch (RuntimeException e) {
			// 最常見的原因：別人同時新增同一代號，搶先寫進了同一列。只記訊息，不印整段 stack trace
			log.warn("新增 {} 時寫入行情快取失敗，持股照常新增，行情由背景排程補上：{}", code, e.toString());
		}
	}
}
