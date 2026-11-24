package io.hsiaopingkuo.stockholdings.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.hsiaopingkuo.stockholdings.calc.DividendEventInput;
import io.hsiaopingkuo.stockholdings.calc.HoldingReplayCalculator;
import io.hsiaopingkuo.stockholdings.calc.OversellException;
import io.hsiaopingkuo.stockholdings.calc.TradeInput;
import io.hsiaopingkuo.stockholdings.calc.TradeSide;
import io.hsiaopingkuo.stockholdings.config.QuoteProperties;
import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.entity.Trade;
import io.hsiaopingkuo.stockholdings.exception.DuplicateCodeException;
import io.hsiaopingkuo.stockholdings.exception.HoldingBusyException;
import io.hsiaopingkuo.stockholdings.exception.MarketAmbiguousException;
import io.hsiaopingkuo.stockholdings.exception.MarketUnavailableException;
import io.hsiaopingkuo.stockholdings.exception.StockCodeNotFoundException;
import io.hsiaopingkuo.stockholdings.exception.TradeNotFoundException;
import io.hsiaopingkuo.stockholdings.exception.TradeOversellException;
import io.hsiaopingkuo.stockholdings.exception.TradeOversellException.EstimatedBuy;
import io.hsiaopingkuo.stockholdings.exception.TradeValidationException;
import io.hsiaopingkuo.stockholdings.exception.VersionConflictException;
import io.hsiaopingkuo.stockholdings.quote.Deadline;
import io.hsiaopingkuo.stockholdings.repository.HoldingRepository;
import io.hsiaopingkuo.stockholdings.repository.TradeRepository;
import io.hsiaopingkuo.stockholdings.service.StockIdentityResolver.StockIdentity;

/**
 * 2.0 的交易新增、修改、刪除（第 6 題第 5a 階段）。App 1.0 的方式仍在 HoldingService，兩邊共用同一張交易表。
 *
 * 每一個寫入都照同樣的順序（設計決定 6-B）：鎖住持股那一列 → 讀出全部交易 → 套用這次的變更重播 →
 * 每個時間點都沒有超賣才寫入 → 交易結束放開鎖。先鎖再重播，才不會「檢查完、寫入前」被別的裝置改掉。
 */
@Service
@Transactional(readOnly = true)
public class TradeService {

	// 新增的交易還沒有 id，重播時先用最大的數字：同一天一定排在最後，和實際寫入後的順序（id 遞增）一致
	private static final long NEW_TRADE_ID = Long.MAX_VALUE;

	// 和 HoldingReplayCalculator 的重播順序相同（日期，同一天再依 id），用來判斷推估的買進是否排在超賣的賣出後面
	private static final Comparator<TradeInput> REPLAY_ORDER = Comparator.comparing(TradeInput::date)
			.thenComparingLong(TradeInput::id);

	private final HoldingRepository holdingRepository;
	private final TradeRepository tradeRepository;
	private final DividendEventProvider dividendEventProvider;
	private final StockIdentityResolver stockIdentityResolver;
	private final Clock clock;
	private final Duration requestBudget;
	// 新增時的短交易：新增要在交易外查官方（F-01），所以用 TransactionTemplate 在程式裡劃出要寫入的那一小段
	private final TransactionTemplate writeTransaction;

	public TradeService(HoldingRepository holdingRepository, TradeRepository tradeRepository,
			DividendEventProvider dividendEventProvider, StockIdentityResolver stockIdentityResolver, Clock clock,
			QuoteProperties quoteProperties, PlatformTransactionManager transactionManager) {
		this.holdingRepository = holdingRepository;
		this.tradeRepository = tradeRepository;
		this.dividendEventProvider = dividendEventProvider;
		this.stockIdentityResolver = stockIdentityResolver;
		this.clock = clock;
		this.requestBudget = quoteProperties.requestBudget();
		this.writeTransaction = new TransactionTemplate(transactionManager);
	}

	// ===== 新增 =====

	/**
	 * 新增一筆買進或賣出。
	 * - 代號已有持股（含股數 0）→ 加在同一筆持股下
	 * - 代號還沒有持股 → 只能買進：新增持股＋這筆買進；市場和名稱照 App 1.0 新增的流程判斷
	 *
	 * @return 寫入後的交易（帶 id、持股 id、版本號）
	 * @throws TradeValidationException   日期、股數、價格不符合規則
	 * @throws TradeOversellException     賣出不夠賣（含沒有持股就賣出）
	 * @throws HoldingBusyException       別的裝置正在修改同一檔，等不到鎖
	 * @throws StockCodeNotFoundException 全新代號：上市、上櫃都查不到
	 * @throws MarketUnavailableException 全新代號：官方網站連不上，無法確認
	 * @throws MarketAmbiguousException   全新代號：兩個市場都查到且無法判斷
	 */
	// F-01：整個方法不開交易，查官方的那幾秒不占資料庫連線、更不占著鎖；寫入各自包在 writeTransaction 裡
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public Trade addTrade(String code, TradeSide side, LocalDate date, long shares, BigDecimal price) {
		return addTrade(code, side, date, shares, price, TradeWriteHook.NONE);
	}

	/**
	 * 同上，另外在每一段寫入交易的前後呼叫 hook（冪等鍵用，第 5b 階段）。
	 * 只有真的寫入交易的那一段會 commit；查既有持股卻沒有持股的那一段一律回滾，hook 在裡面寫的東西也不會留下。
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public Trade addTrade(String code, TradeSide side, LocalDate date, long shares, BigDecimal price,
			TradeWriteHook hook) {
		// 時間預算從收到請求就開始算，整個請求才不會超過 10 秒（同 HoldingService.create）
		Deadline deadline = Deadline.after(requestBudget);
		if (code == null || code.isBlank()) {
			throw new TradeValidationException("code", "請輸入股票代號");
		}
		if (side == null) {
			throw new TradeValidationException("side", "請選擇買進或賣出");
		}
		// 使用者自己填的日期，一律檢查週末
		TradeRules.checkDate(date, today(), true);
		TradeRules.checkShares(shares);
		TradeRules.checkPrice(price);
		String normalizedCode = HoldingService.normalizeCode(code);

		// 先看代號是否已有持股：有就鎖住、加在下面。放在查官方之前，既有持股的市場和名稱已經知道
		Trade added = writeWithHook(hook, () -> addToExisting(normalizedCode, side, date, shares, price));
		if (added != null) {
			return added;
		}
		if (side == TradeSide.SELL) {
			throw TradeOversellException.noHolding(normalizedCode);
		}

		// 全新代號：在交易和鎖之外判斷市場和名稱（可能要問官方）
		StockIdentity identity = stockIdentityResolver.resolve(normalizedCode, deadline);
		try {
			return writeWithHook(hook, () -> {
				// saveAndFlush：立刻送出 INSERT，違反 UNIQUE 時在這裡就丟例外，才接得到
				Holding saved = holdingRepository
						.saveAndFlush(new Holding(normalizedCode, identity.name(), identity.market(), now()));
				return tradeRepository.save(new Trade(saved.getId(), side, date, shares, price, false, now()));
			});
		} catch (DataIntegrityViolationException e) {
			if (!HoldingService.isUniqueCodeViolation(e)) {
				throw e;
			}
			// 查官方的這段期間，別人剛好也新增了同一個代號：全新代號沒有那一列可以鎖，由 UNIQUE 擋下。
			// 交易可以多筆，所以不回「重複代號」，改加在對方剛建好的持股下（一樣要鎖住、重播檢查）
			Trade retried = writeWithHook(hook, () -> addToExisting(normalizedCode, side, date, shares, price));
			if (retried == null) {
				// 對方剛建好又馬上刪掉，極少見；請使用者再送一次
				throw new DuplicateCodeException(normalizedCode);
			}
			return retried;
		}
	}

	/**
	 * 新增用的一段短交易：開頭呼叫 hook.beforeLock()（鎖持股之前），寫入後呼叫 hook.afterWrite()，再一起 commit。
	 * write 回 null（代號還沒有持股、沒有寫入）時整段回滾：hook 寫的冪等鍵不能先 commit，
	 * 否則重送會被當成「已完成」，交易卻沒寫進去。
	 *
	 * @return 寫入的交易；write 回 null 時回 null
	 */
	private Trade writeWithHook(TradeWriteHook hook, Supplier<Trade> write) {
		return writeTransaction.execute(status -> {
			hook.beforeLock();
			Trade written = write.get();
			if (written == null) {
				status.setRollbackOnly();
				return null;
			}
			hook.afterWrite(written);
			return written;
		});
	}

	/**
	 * 代號已有持股時（在 writeTransaction 裡執行）：鎖住、把新交易放進清單重播，沒有超賣才寫入。
	 *
	 * @return 寫入的交易；代號還沒有持股時回 null
	 */
	private Trade addToExisting(String code, TradeSide side, LocalDate date, long shares, BigDecimal price) {
		Optional<Holding> found = HoldingService.lock(() -> holdingRepository.findByCodeForUpdate(code));
		if (found.isEmpty()) {
			return null;
		}
		Holding holding = found.get();
		List<Trade> trades = tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(holding.getId());

		List<TradeInput> after = new ArrayList<>(trades.stream().map(Trade::toInput).toList());
		after.add(new TradeInput(NEW_TRADE_ID, date, side, shares, price));
		// 新交易的日期是使用者填的，不是推估
		checkNoOversell(holding, after, estimatedIds(trades, null));

		return tradeRepository.save(new Trade(holding.getId(), side, date, shares, price, false, now()));
	}

	// ===== 修改 =====

	/**
	 * 修改交易的日期、股數、價格（買賣方向不能改）。
	 *
	 * @param expectedVersion 使用者畫面上看到的版本號
	 * @return 修改後的交易（版本號已加 1）
	 * @throws TradeNotFoundException   找不到這筆交易
	 * @throws VersionConflictException 別的裝置已經改過
	 * @throws TradeValidationException 日期、股數、價格不符合規則
	 * @throws TradeOversellException   改完之後某一筆賣出不夠賣
	 * @throws HoldingBusyException     別的裝置正在修改同一檔，等不到鎖
	 */
	@Transactional
	public Trade updateTrade(Long tradeId, long expectedVersion, LocalDate date, long shares, BigDecimal price) {
		return updateTrade(tradeId, expectedVersion, date, shares, price, TradeWriteHook.NONE);
	}

	/** 同上，另外在這個資料庫交易的開頭（鎖持股之前）和寫入後呼叫 hook（冪等鍵用，第 5b 階段）。 */
	@Transactional
	public Trade updateTrade(Long tradeId, long expectedVersion, LocalDate date, long shares, BigDecimal price,
			TradeWriteHook hook) {
		hook.beforeLock();
		LockedTrade locked = lockByTrade(tradeId);
		Trade target = locked.target();
		checkVersion(target, expectedVersion);

		boolean dateChanged = !Objects.equals(target.getTradeDate(), date);
		// 推估的日期沒有被改動時不檢查週末（決定 9）；日期改了就是使用者自己填的，要檢查
		TradeRules.checkDate(date, today(), dateChanged || !target.isDateEstimated());
		TradeRules.checkShares(shares);
		TradeRules.checkPrice(price);

		List<TradeInput> after = locked.trades().stream()
				.map(t -> t == target ? new TradeInput(t.getId(), date, t.getSide(), shares, price) : t.toInput())
				.toList();
		// 日期改了，這筆就不再是推估；只改價格、股數時仍是推估
		checkNoOversell(locked.holding(), after, estimatedIds(locked.trades(), dateChanged ? target : null));

		target.changeDetails(date, shares, price, now());
		// 立刻送出 UPDATE：版本號在這時加 1，回傳給呼叫端的才是新的版本號
		tradeRepository.flush();
		hook.afterWrite(target);
		return target;
	}

	// ===== 刪除 =====

	/**
	 * 刪除一筆交易。刪掉的是這檔持股的最後一筆交易時，連持股一起刪（一筆交易都沒有的持股沒有東西可以顯示）；
	 * 賣光但還有交易的持股照樣保留（有已實現損益等歷史）。
	 *
	 * @throws TradeNotFoundException   找不到這筆交易
	 * @throws VersionConflictException 別的裝置已經改過
	 * @throws TradeOversellException   刪掉之後某一筆賣出不夠賣（例如刪掉以前的買進）
	 * @throws HoldingBusyException     別的裝置正在修改同一檔，等不到鎖
	 */
	@Transactional
	public void deleteTrade(Long tradeId, long expectedVersion) {
		deleteTrade(tradeId, expectedVersion, TradeWriteHook.NONE);
	}

	/** 同上，另外在這個資料庫交易的開頭（鎖持股之前）和刪除後呼叫 hook（冪等鍵用，第 5b 階段）。 */
	@Transactional
	public void deleteTrade(Long tradeId, long expectedVersion, TradeWriteHook hook) {
		hook.beforeLock();
		LockedTrade locked = lockByTrade(tradeId);
		Trade target = locked.target();
		checkVersion(target, expectedVersion);

		List<Trade> remaining = locked.trades().stream().filter(t -> t != target).toList();
		if (!remaining.isEmpty()) {
			checkNoOversell(locked.holding(), remaining.stream().map(Trade::toInput).toList(),
					estimatedIds(remaining, null));
		}

		tradeRepository.delete(target);
		if (remaining.isEmpty()) {
			// 交易先刪、持股後刪，不依賴外鍵的 ON DELETE CASCADE，讀程式就看得出刪了什麼
			holdingRepository.delete(locked.holding());
		}
		hook.afterWrite(target);
	}

	// ===== 共用 =====

	/**
	 * 修改、刪除共用：先查出交易屬於哪一檔持股 → 鎖住持股 → 才讀出這一檔的全部交易。
	 * 鎖住之後才讀，讀到的一定是最新的（別的寫入都要先拿到同一把鎖）。
	 */
	private LockedTrade lockByTrade(Long tradeId) {
		Long holdingId = tradeRepository.findHoldingIdById(tradeId)
				.orElseThrow(() -> new TradeNotFoundException(tradeId));
		// 查出 holdingId 之後、鎖住之前，持股可能剛被刪掉（連同交易）
		Holding holding = HoldingService.lock(() -> holdingRepository.findByIdForUpdate(holdingId))
				.orElseThrow(() -> new TradeNotFoundException(tradeId));
		List<Trade> trades = tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(holdingId);
		// 同上，交易本身可能在鎖住之前剛被刪掉
		Trade target = trades.stream().filter(t -> t.getId().equals(tradeId)).findFirst()
				.orElseThrow(() -> new TradeNotFoundException(tradeId));
		return new LockedTrade(holding, trades, target);
	}

	/** 鎖住後的狀態：持股、它的全部交易（重播順序）、要修改或刪除的那一筆。 */
	private record LockedTrade(Holding holding, List<Trade> trades, Trade target) {
	}

	private static void checkVersion(Trade trade, long expectedVersion) {
		if (trade.getVersion() != expectedVersion) {
			throw new VersionConflictException();
		}
	}

	/**
	 * 用「套用這次變更之後」的交易清單重播，每個時間點都不能超賣。
	 * 只檢查變更後的清單：原本就異常的資料（手動改過資料庫），只要這次的修改能讓它恢復正常就放行，使用者才修得好。
	 *
	 * @param estimatedIds 變更後仍是推估日期的交易 id
	 */
	private void checkNoOversell(Holding holding, List<TradeInput> after, List<Long> estimatedIds) {
		try {
			// 只需要知道會不會超賣，不需要收盤價
			HoldingReplayCalculator.replay(holding.getCode(), after, eventsOf(holding.getCode()), null);
		} catch (OversellException e) {
			Long oversoldId = e.getTradeId() == NEW_TRADE_ID ? null : e.getTradeId();
			throw TradeOversellException.from(e, oversoldId, estimatedBuyBlocking(after, estimatedIds, e));
		}
	}

	/**
	 * 決定 1：超賣的賣出後面如果有推估日期的買進，很可能是那筆買進的實際日期更早，只是被推估成較晚的日期。
	 * 找出重播順序中，排在這筆賣出之後的第一筆推估買進，讓錯誤訊息指出它。
	 */
	private static EstimatedBuy estimatedBuyBlocking(List<TradeInput> after, List<Long> estimatedIds,
			OversellException e) {
		Map<Long, TradeInput> byId = after.stream().collect(Collectors.toMap(TradeInput::id, Function.identity()));
		TradeInput oversold = byId.get(e.getTradeId());
		return after.stream()
				.filter(t -> t.side() == TradeSide.BUY && estimatedIds.contains(t.id()))
				.filter(t -> REPLAY_ORDER.compare(t, oversold) > 0)
				.min(REPLAY_ORDER)
				.map(t -> new EstimatedBuy(t.id(), t.date(), t.shares()))
				.orElse(null);
	}

	/**
	 * 日期是推估的交易 id。
	 *
	 * @param noLongerEstimated 這次改了日期、不再算推估的那一筆；沒有時傳 null
	 */
	private static List<Long> estimatedIds(List<Trade> trades, Trade noLongerEstimated) {
		return trades.stream()
				.filter(t -> t.isDateEstimated() && t != noLongerEstimated)
				.map(Trade::getId)
				.toList();
	}

	private List<DividendEventInput> eventsOf(String code) {
		return dividendEventProvider.eventsByCode(List.of(code)).getOrDefault(code, List.of());
	}

	private LocalDate today() {
		// 一定要傳 clock（台北時區），不能用沒參數的 now()
		return LocalDate.now(clock);
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock);
	}
}
