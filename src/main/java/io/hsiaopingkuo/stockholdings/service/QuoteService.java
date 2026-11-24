package io.hsiaopingkuo.stockholdings.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.hsiaopingkuo.stockholdings.entity.Quote;
import io.hsiaopingkuo.stockholdings.quote.DailyQuote;
import io.hsiaopingkuo.stockholdings.repository.QuoteRepository;

/** 行情快取（quote 表）的讀寫。寫入規則在 {@link Quote#apply}，這裡負責「一次寫一整批」。 */
@Service
@Transactional(readOnly = true)
public class QuoteService {

	private final QuoteRepository quoteRepository;
	private final Clock clock;

	public QuoteService(QuoteRepository quoteRepository, Clock clock) {
		this.quoteRepository = quoteRepository;
		this.clock = clock;
	}

	public Optional<Quote> findByCode(String code) {
		return quoteRepository.findById(code);
	}

	/**
	 * 寫入一批行情（批次查詢一次約 1000～1400 筆）。
	 * 先一次把已存在的列讀出來，再逐筆套用規則：沒有的新增，已有的交給 Quote.apply 判斷要不要更新。
	 * 不在這一批裡的代號完全不動。
	 *
	 * @return 新增或真的有變動的筆數
	 */
	@Transactional
	public int upsert(Collection<DailyQuote> quotes) {
		if (quotes.isEmpty()) {
			return 0;
		}
		LocalDateTime now = LocalDateTime.now(clock);
		// 一次查回來，不要每筆查一次資料庫
		Map<String, Quote> existing = new HashMap<>();
		quoteRepository.findAllById(quotes.stream().map(DailyQuote::code).toList())
				.forEach(quote -> existing.put(quote.getCode(), quote));

		List<Quote> created = new ArrayList<>();
		int changed = 0;
		for (DailyQuote incoming : quotes) {
			Quote quote = existing.get(incoming.code());
			if (quote == null) {
				quote = new Quote(incoming, now);
				// 放進 map：同一批裡若重複出現同一代號，第二次走更新，不會新增兩列
				existing.put(incoming.code(), quote);
				created.add(quote);
				changed++;
			} else if (quote.apply(incoming, now)) {
				// 已存在的列是交易中讀出來的，改了欄位後交易結束會自動寫回，不用呼叫 save
				changed++;
			}
		}
		quoteRepository.saveAll(created);
		return changed;
	}
}
