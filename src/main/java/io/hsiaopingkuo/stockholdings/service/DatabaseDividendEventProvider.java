package io.hsiaopingkuo.stockholdings.service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.hsiaopingkuo.stockholdings.calc.DividendEventInput;
import io.hsiaopingkuo.stockholdings.entity.DividendEvent;
import io.hsiaopingkuo.stockholdings.repository.DividendEventRepository;

/**
 * 從 dividend_event 表讀除權息事件給重播計算用（第 6 題）。只讀資料庫，不呼叫官方 API：
 * 寫入交易時會在鎖住持股的交易裡呼叫它（F-01）。資料由背景的 DividendSyncService 寫入。
 */
@Component
public class DatabaseDividendEventProvider implements DividendEventProvider {

	private final DividendEventRepository eventRepository;

	public DatabaseDividendEventProvider(DividendEventRepository eventRepository) {
		this.eventRepository = eventRepository;
	}

	/**
	 * 不分市場：一檔股票從上櫃轉上市時，兩個市場的事件都屬於這個代號。
	 * 「無法取得」的事件也提供（數字是 null），重播才會列進明細、提示使用者。
	 */
	@Override
	@Transactional(readOnly = true)
	public Map<String, List<DividendEventInput>> eventsByCode(Collection<String> codes) {
		if (codes.isEmpty()) {
			// 空的 IN () 在部分資料庫是語法錯誤；本來也查不到東西
			return Map.of();
		}
		// 查詢已經依代號、除權息日排好，groupingBy 用 LinkedHashMap、toList 會保留這個順序
		return eventRepository.findByCodeInOrderByCodeAscExDateAscIdAsc(new LinkedHashSet<>(codes)).stream()
				.collect(Collectors.groupingBy(DividendEvent::getCode, LinkedHashMap::new,
						Collectors.mapping(DividendEvent::toInput, Collectors.toList())));
	}
}
