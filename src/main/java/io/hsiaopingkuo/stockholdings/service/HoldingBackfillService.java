package io.hsiaopingkuo.stockholdings.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.random.RandomGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.hsiaopingkuo.stockholdings.config.QuoteProperties;
import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.entity.Quote;
import io.hsiaopingkuo.stockholdings.exception.MarketUnavailableException;
import io.hsiaopingkuo.stockholdings.exception.StockCodeNotFoundException;
import io.hsiaopingkuo.stockholdings.quote.MarketResolver;
import io.hsiaopingkuo.stockholdings.quote.StockLookup;
import io.hsiaopingkuo.stockholdings.repository.HoldingRepository;

/**
 * 補上第 2 題時存的舊持股的市場和名稱（那時還沒有判斷上市上櫃，market 是 NULL）。
 * 伺服器啟動時和排程每一輪都會呼叫；沒有舊資料時只做一次資料庫查詢，幾乎不花時間。
 *
 * 順序：先查行情快取（批次寫入後大多查得到，不用打官方），查不到才問官方，
 * 問官方時遵守和逐檔查詢相同的請求間隔。
 */
@Service
public class HoldingBackfillService {

	private static final Logger log = LoggerFactory.getLogger(HoldingBackfillService.class);

	private final HoldingRepository holdingRepository;
	private final QuoteService quoteService;
	private final MarketResolver marketResolver;
	private final Pause pause;
	private final RandomGenerator random;
	private final QuoteProperties.Refresh settings;
	private final Clock clock;
	// 上市、上櫃兩個排程都會呼叫這裡；同一時間只讓一個在補，另一個直接略過，避免同一代號被查兩次
	private final ReentrantLock lock = new ReentrantLock();

	public HoldingBackfillService(HoldingRepository holdingRepository, QuoteService quoteService,
			MarketResolver marketResolver, Pause pause, RandomGenerator random, QuoteProperties properties,
			Clock clock) {
		this.holdingRepository = holdingRepository;
		this.quoteService = quoteService;
		this.marketResolver = marketResolver;
		this.pause = pause;
		this.random = random;
		this.settings = properties.refresh();
		this.clock = clock;
	}

	/**
	 * @param officialAllowed 可不可以呼叫官方；任一市場在冷卻時傳 false（MarketResolver 會同時查兩個市場），只用快取補
	 * @return 補了幾筆
	 */
	public int backfill(boolean officialAllowed) {
		if (!lock.tryLock()) {
			return 0;
		}
		int filled = 0;
		try {
			List<Holding> missing = holdingRepository.findByMarketIsNullOrderByCodeAsc();
			boolean firstOfficialCall = true;
			for (Holding holding : missing) {
				Optional<Quote> cached = quoteService.findByCode(holding.getCode());
				if (cached.isPresent()) {
					filled += fill(holding, cached.get().getMarket(), cached.get().getName());
					continue;
				}
				if (!officialAllowed) {
					continue;
				}
				if (!firstOfficialCall) {
					// 一次只送一個請求，請求之間隨機等一下，避免被官方擋
					pause.sleep(Pause.randomBetween(settings.perRequestDelayMin(), settings.perRequestDelayMax(), random));
				}
				firstOfficialCall = false;
				try {
					StockLookup lookup = marketResolver.resolve(holding.getCode());
					quoteService.upsert(List.of(lookup.toDailyQuote(holding.getCode())));
					filled += fill(holding, lookup.market(), lookup.name());
				} catch (StockCodeNotFoundException e) {
					// 上市上櫃都沒有（可能已下市）：留著 NULL，清單顯示「—」，不影響其他持股
					log.warn("補查舊持股 {}：上市、上櫃都查不到", holding.getCode());
				} catch (MarketUnavailableException e) {
					// 官方連不上：再查下一檔也只會失敗，這次先停，下一輪再補
					log.warn("補查舊持股 {}：官方暫時連不上，這次先停止", holding.getCode());
					break;
				}
			}
			if (filled > 0) {
				log.info("補上 {} 筆舊持股的市場和名稱", filled);
			}
		} catch (InterruptedException e) {
			// 伺服器關閉中：保留中斷狀態，直接結束
			Thread.currentThread().interrupt();
		} finally {
			lock.unlock();
		}
		return filled;
	}

	private int fill(Holding holding, Market market, String name) {
		return holdingRepository.fillMarket(holding.getId(), market, name, LocalDateTime.now(clock));
	}
}
