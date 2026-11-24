package io.hsiaopingkuo.stockholdings.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.hsiaopingkuo.stockholdings.calc.DividendDataStatus;
import io.hsiaopingkuo.stockholdings.calc.DividendStatusRule;
import io.hsiaopingkuo.stockholdings.entity.DividendDetailStatus;
import io.hsiaopingkuo.stockholdings.entity.DividendEvent;
import io.hsiaopingkuo.stockholdings.entity.DividendFetchRange;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.repository.DividendEventRepository;
import io.hsiaopingkuo.stockholdings.repository.DividendFetchRangeRepository;

/**
 * 每檔持股的股利資料狀態（第 6 題設計決定 7-D）。第 6 階段的清單和 /api/v2 呼叫；這一階段還沒有畫面使用。
 * 不論幾檔持股都只查兩次資料庫（已抓範圍、待查的事件），在記憶體裡套用 DividendStatusRule。只讀資料庫。
 */
@Service
@Transactional(readOnly = true)
public class DividendStatusService {

	/**
	 * 一檔持股要判斷的範圍。
	 *
	 * @param market         持股的市場；第 2 題的舊資料還沒補到市場時是 null（兩個市場都要看）
	 * @param firstTradeDate 第一筆交易日；沒有交易時是 null
	 */
	public record HoldingScope(String code, Market market, LocalDate firstTradeDate) {
	}

	private final DividendFetchRangeRepository rangeRepository;
	private final DividendEventRepository eventRepository;
	private final Clock clock;

	public DividendStatusService(DividendFetchRangeRepository rangeRepository,
			DividendEventRepository eventRepository, Clock clock) {
		this.rangeRepository = rangeRepository;
		this.eventRepository = eventRepository;
		this.clock = clock;
	}

	/** @return 代號 → 股利資料狀態，順序和傳入的相同 */
	public Map<String, DividendDataStatus> statusByCode(Collection<HoldingScope> holdings) {
		if (holdings.isEmpty()) {
			return Map.of();
		}
		LocalDate today = LocalDate.now(clock);

		// 市場 → (年份 → 抓完了沒)
		Map<Market, Map<Integer, Boolean>> rangesByMarket = new EnumMap<>(Market.class);
		for (DividendFetchRange range : rangeRepository.findAll()) {
			rangesByMarket.computeIfAbsent(range.getMarket(), market -> new HashMap<>())
					.put(range.getFetchYear(), range.isCompleted());
		}

		Set<String> codes = holdings.stream().map(HoldingScope::code).collect(Collectors.toCollection(LinkedHashSet::new));
		Map<String, List<LocalDate>> pendingExDatesByCode = eventRepository
				.findByDetailStatusAndCodeIn(DividendDetailStatus.PENDING, codes).stream()
				.collect(Collectors.groupingBy(DividendEvent::getCode,
						Collectors.mapping(DividendEvent::getExDate, Collectors.toList())));

		Map<String, DividendDataStatus> result = new LinkedHashMap<>();
		for (HoldingScope holding : holdings) {
			List<Market> markets = holding.market() != null ? List.of(holding.market()) : List.of(Market.values());
			List<Map<Integer, Boolean>> fetchRanges = markets.stream()
					.map(market -> rangesByMarket.getOrDefault(market, Map.of()))
					.toList();
			// 第一筆交易之前的事件不影響這檔，待查也不算
			boolean hasPendingDetail = holding.firstTradeDate() != null
					&& pendingExDatesByCode.getOrDefault(holding.code(), List.of()).stream()
							.anyMatch(exDate -> exDate.isAfter(holding.firstTradeDate()));
			result.put(holding.code(),
					DividendStatusRule.status(holding.firstTradeDate(), today, fetchRanges, hasPendingDetail));
		}
		return result;
	}
}
