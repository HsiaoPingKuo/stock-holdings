package io.hsiaopingkuo.stockholdings.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.hsiaopingkuo.stockholdings.entity.DividendDetailStatus;
import io.hsiaopingkuo.stockholdings.entity.DividendEvent;
import io.hsiaopingkuo.stockholdings.entity.DividendFetchRange;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.quote.DividendDetailResult;
import io.hsiaopingkuo.stockholdings.quote.DividendListRow;
import io.hsiaopingkuo.stockholdings.repository.DividendEventRepository;
import io.hsiaopingkuo.stockholdings.repository.DividendFetchRangeRepository;

/**
 * 除權息同步的寫入（第 6 題）。每個方法是一個短交易，裡面只有資料庫的動作。
 * 和 DividendSyncService 分成兩個類別：同一個類別裡呼叫自己標 @Transactional 的方法不會經過 Spring 的代理，
 * 交易不會生效；拆開後「呼叫官方」（在 DividendSyncService，不在交易裡）和「寫入」一定在不同的地方（F-01）。
 */
@Service
public class DividendEventWriter {

	private static final Logger log = LoggerFactory.getLogger(DividendEventWriter.class);

	/** 一次寫入列表的結果：新增幾筆、數值真的有變的幾筆。 */
	public record ListWriteResult(int inserted, int updated) {
	}

	private final DividendEventRepository eventRepository;
	private final DividendFetchRangeRepository rangeRepository;

	public DividendEventWriter(DividendEventRepository eventRepository, DividendFetchRangeRepository rangeRepository) {
		this.eventRepository = eventRepository;
		this.rangeRepository = rangeRepository;
	}

	/**
	 * 存一年的列表，並記錄這一年抓過了。
	 *
	 * @param rows      這一年的列表（client 回 OK 時才會有，不會是空的）
	 * @param completed 是不是在那一年結束之後才開始抓的
	 */
	@Transactional
	public ListWriteResult saveListYear(Market market, int year, List<DividendListRow> rows, boolean completed,
			LocalDateTime now) {
		// 同一檔同一天出現不只一列時要特別處理，所以先分組；LinkedHashMap 保留列表原本的順序
		Map<DividendEvent.Key, List<DividendListRow>> rowsByKey = rows.stream()
				.collect(Collectors.groupingBy(DividendEvent.Key::of, LinkedHashMap::new, Collectors.toList()));

		// 期間包含整年，也包含列表裡所有的日期（官方照理不會回範圍外的日期，萬一有也不會因此新增重複的一列）
		LocalDate start = LocalDate.of(year, 1, 1);
		LocalDate end = LocalDate.of(year, 12, 31);
		for (DividendEvent.Key key : rowsByKey.keySet()) {
			start = key.exDate().isBefore(start) ? key.exDate() : start;
			end = key.exDate().isAfter(end) ? key.exDate() : end;
		}
		Map<DividendEvent.Key, DividendEvent> existing = new HashMap<>();
		eventRepository.findByMarketAndExDateBetween(market, start, end)
				.forEach(event -> existing.put(event.key(), event));

		List<DividendEvent> created = new ArrayList<>();
		int updated = 0;
		for (Map.Entry<DividendEvent.Key, List<DividendListRow>> entry : rowsByKey.entrySet()) {
			List<DividendListRow> sameDay = entry.getValue();
			DividendListRow first = sameDay.get(0);
			boolean ambiguous = sameDay.size() > 1;
			if (ambiguous) {
				// 實測沒有這種情況；兩列都不一定對，不猜數字，存成「無法取得」，整份列表照常寫入
				log.warn("除權息列表 {} {}：{} {} 出現 {} 列，存成「無法取得」", market, year, first.code(),
						first.exDate(), sameDay.size());
			}
			DividendEvent event = existing.remove(entry.getKey());
			if (event == null) {
				// 已存在的列是交易中讀出來的，改了欄位後交易結束會自動寫回；新的才要 save
				event = new DividendEvent(first, ambiguous, now);
				created.add(event);
			} else if (ambiguous ? event.applyAmbiguousListRow(first, now) : event.applyListRow(first, now)) {
				updated++;
			}
			warnIfNoDetailParam(event);
		}
		eventRepository.saveAll(created);

		// 重抓時列表裡不見的事件：不刪（刪掉會讓股數突然變少，實測也沒看過官方刪資料），只記 WARN
		existing.values().stream()
				.filter(event -> event.getExDate().getYear() == year)
				.forEach(event -> log.warn("除權息列表 {} {}：資料庫有 {}，這次的列表沒有，保留不刪", market, year, event));

		markFetched(market, year, completed, now);
		return new ListWriteResult(created.size(), updated);
	}

	/**
	 * 上市、需要明細、但列表沒有「詳細資料」參數：沒辦法查，直接標成查不到（沒有查詢時間）。記下來方便追查。
	 */
	private static void warnIfNoDetailParam(DividendEvent event) {
		if (event.getDetailStatus() == DividendDetailStatus.NOT_FOUND && event.getDetailCheckedAt() == null) {
			log.warn("除權息列表：{} 需要明細但沒有明細參數，標成查不到", event);
		}
	}

	/** 官方回應正常但那一年沒有除權息：一樣記成抓過，才分得出「沒有除權息」和「還沒抓」。 */
	@Transactional
	public void markYearFetched(Market market, int year, boolean completed, LocalDateTime now) {
		markFetched(market, year, completed, now);
	}

	private void markFetched(Market market, int year, boolean completed, LocalDateTime now) {
		rangeRepository.findByMarketAndFetchYear(market, year).ifPresentOrElse(
				range -> range.markFetched(completed, now),
				() -> rangeRepository.save(new DividendFetchRange(market, year, completed, now)));
	}

	/**
	 * 存一次明細查詢的結果（FOUND／NOT_FOUND／FAILED；被擋不會傳進來）。
	 *
	 * @return 存完之後的明細狀態；事件找不到時（不會發生，事件不會被刪）是 null
	 */
	@Transactional
	public DividendDetailStatus saveDetailResult(Long eventId, DividendDetailResult result, LocalDateTime now) {
		return eventRepository.findById(eventId)
				.map(event -> {
					event.applyDetailResult(result, now);
					return event.getDetailStatus();
				})
				.orElse(null);
	}
}
