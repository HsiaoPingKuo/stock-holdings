package io.hsiaopingkuo.stockholdings.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.hsiaopingkuo.stockholdings.calc.DividendDataStatus;
import io.hsiaopingkuo.stockholdings.calc.DividendEventInput;
import io.hsiaopingkuo.stockholdings.calc.HoldingReplayCalculator;
import io.hsiaopingkuo.stockholdings.calc.HoldingReplayResult;
import io.hsiaopingkuo.stockholdings.calc.OversellException;
import io.hsiaopingkuo.stockholdings.dto.HoldingQuoteRow;
import io.hsiaopingkuo.stockholdings.dto.HoldingV2View;
import io.hsiaopingkuo.stockholdings.entity.Trade;
import io.hsiaopingkuo.stockholdings.exception.HoldingNotFoundException;
import io.hsiaopingkuo.stockholdings.repository.HoldingRepository;
import io.hsiaopingkuo.stockholdings.repository.TradeRepository;
import io.hsiaopingkuo.stockholdings.service.DividendStatusService.HoldingScope;

/**
 * /api/v2 的讀取（第 6 題）。只讀資料庫（行情快取、交易、除權息事件），不呼叫官方：
 * 寫入後組回應時是在鎖住持股的交易裡呼叫，等官方會讓其他裝置一直等不到鎖（F-01）。
 *
 * 和 v1 的 HoldingService.findAllForList 不同：已賣光（股數 0）但還有交易的持股也列出（有已實現損益、股利）。
 */
@Service
@Transactional(readOnly = true)
public class HoldingV2QueryService {

	private static final Logger log = LoggerFactory.getLogger(HoldingV2QueryService.class);

	private final HoldingRepository holdingRepository;
	private final TradeRepository tradeRepository;
	private final DividendEventProvider dividendEventProvider;
	private final DividendStatusService dividendStatusService;

	public HoldingV2QueryService(HoldingRepository holdingRepository, TradeRepository tradeRepository,
			DividendEventProvider dividendEventProvider, DividendStatusService dividendStatusService) {
		this.holdingRepository = holdingRepository;
		this.tradeRepository = tradeRepository;
		this.dividendEventProvider = dividendEventProvider;
		this.dividendStatusService = dividendStatusService;
	}

	/** 全部持股，依代號排序。持股再多也只查固定幾次資料庫，在記憶體裡組合。 */
	public List<HoldingV2View> findAll() {
		List<HoldingQuoteRow> rows = holdingRepository.findAllForList();
		// 查詢已依持股、日期、id 排好，groupingBy 會保留每一檔裡的順序
		Map<Long, List<Trade>> tradesByHolding = tradeRepository.findAllByOrderByHoldingIdAscTradeDateAscIdAsc()
				.stream()
				.collect(Collectors.groupingBy(Trade::getHoldingId));
		return assemble(rows, tradesByHolding);
	}

	/**
	 * 單筆持股（含已賣光的）。
	 *
	 * @throws HoldingNotFoundException 找不到，或一筆交易都沒有
	 */
	public HoldingV2View find(Long id) {
		return findOptional(id).orElseThrow(() -> new HoldingNotFoundException(id));
	}

	/**
	 * 寫入交易後組回應用：在 TradeService 的資料庫交易裡呼叫（MANDATORY：沒有交易就是用錯了），
	 * 先 flush 把這次的新增、修改、刪除送進資料庫，下面的查詢才看得到。
	 *
	 * @return 持股的最新狀態；刪掉最後一筆交易、持股一起被刪時是 empty
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<HoldingV2View> findAfterWrite(Long holdingId) {
		tradeRepository.flush();
		return findOptional(holdingId);
	}

	private Optional<HoldingV2View> findOptional(Long id) {
		Optional<HoldingQuoteRow> row = holdingRepository.findListItemById(id);
		if (row.isEmpty()) {
			return Optional.empty();
		}
		List<Trade> trades = tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(id);
		return assemble(List.of(row.get()), Map.of(id, trades)).stream().findFirst();
	}

	/** 一筆交易都沒有的持股不列出：沒有東西可以顯示（正常情況刪掉最後一筆交易時持股會一起刪）。 */
	private List<HoldingV2View> assemble(List<HoldingQuoteRow> rows, Map<Long, List<Trade>> tradesByHolding) {
		List<HoldingQuoteRow> withTrades = rows.stream()
				.filter(row -> !tradesByHolding.getOrDefault(row.id(), List.of()).isEmpty())
				.toList();
		Map<String, List<DividendEventInput>> eventsByCode = dividendEventProvider
				.eventsByCode(withTrades.stream().map(HoldingQuoteRow::code).toList());
		Map<String, DividendDataStatus> statusByCode = dividendStatusService.statusByCode(withTrades.stream()
				.map(row -> new HoldingScope(row.code(), row.market(),
						tradesByHolding.get(row.id()).get(0).getTradeDate()))
				.toList());

		return withTrades.stream()
				.map(row -> toView(row, tradesByHolding.get(row.id()),
						eventsByCode.getOrDefault(row.code(), List.of()), statusByCode.get(row.code())))
				.toList();
	}

	/**
	 * 重播時超賣不往外丟（同 HoldingAssembler）：寫入前都檢查過超賣，讀取時還發生代表資料被手動改過。
	 * 記 log、replay 是 null 標成「資料異常」，並記下是哪一筆賣出（網頁明細要標出來），其他持股照常顯示。
	 */
	private static HoldingV2View toView(HoldingQuoteRow row, List<Trade> trades, List<DividendEventInput> events,
			DividendDataStatus status) {
		try {
			HoldingReplayResult replay = HoldingReplayCalculator.replay(row.code(),
					trades.stream().map(Trade::toInput).toList(), events,
					row.hasClosePrice() ? row.closePrice() : null);
			return new HoldingV2View(row, trades, replay, status, null);
		} catch (OversellException e) {
			log.warn("持股 {}（id={}）重播交易失敗，資料可能被手動修改：{}", row.code(), row.id(), e.getMessage());
			return new HoldingV2View(row, trades, null, status, e.getTradeId());
		}
	}
}
