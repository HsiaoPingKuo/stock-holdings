package io.hsiaopingkuo.stockholdings.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.hsiaopingkuo.stockholdings.entity.Quote;
import io.hsiaopingkuo.stockholdings.quote.DailyQuote;
import io.hsiaopingkuo.stockholdings.repository.QuoteRepository;

/** 行情快取（quote 表）的讀寫。寫入規則在 {@link Quote#apply}，這裡負責「一次寫一整批」。 */
@Service
@Transactional(readOnly = true)
public class QuoteService {

	private static final Logger log = LoggerFactory.getLogger(QuoteService.class);

	// V3__create_quote.sql 的主鍵約束名稱；H2 的錯誤訊息裡會出現它（大寫）
	private static final String PRIMARY_KEY_CONSTRAINT = "PK_QUOTE";
	// 主鍵衝突時整批重做一次就夠：重做時那一列已經存在，會被鎖住再比較，不會再衝突
	private static final int MAX_ATTEMPTS = 2;

	private final QuoteRepository quoteRepository;
	private final Clock clock;
	// 交易由 TransactionTemplate 在程式裡開：主鍵衝突時要在交易外接住例外，再開一個新交易重做
	private final TransactionTemplate writeTransaction;

	public QuoteService(QuoteRepository quoteRepository, Clock clock, PlatformTransactionManager transactionManager) {
		this.quoteRepository = quoteRepository;
		this.clock = clock;
		this.writeTransaction = new TransactionTemplate(transactionManager);
	}

	public Optional<Quote> findByCode(String code) {
		return quoteRepository.findById(code);
	}

	/**
	 * 寫入一批行情（批次查詢一次約 1000～1400 筆）。
	 * 先鎖住已存在的列，再逐筆套用規則：沒有的新增，已有的交給 Quote.apply 判斷要不要更新。
	 * 不在這一批裡的代號完全不動。
	 *
	 * 兩方同時新增同一個新代號時（沒有列可以鎖），後提交的一方會撞到主鍵：整批換一個新交易重做一次，
	 * 不讓一千多筆因為一筆衝突全部失敗。等不到鎖則直接丟出，由呼叫端決定（排程下一輪再試、新增持股只記 log）。
	 *
	 * SUPPORTS：自己不開交易（類別預設的唯讀交易會讓寫入不送出），交給 TransactionTemplate 開；
	 * 呼叫端已經在交易裡時（例如測試）就加入那個交易。
	 *
	 * @return 新增或真的有變動的筆數
	 */
	@Transactional(propagation = Propagation.SUPPORTS)
	public int upsert(Collection<DailyQuote> quotes) {
		if (quotes.isEmpty()) {
			return 0;
		}
		// 依代號排序：新增的列也照這個順序寫入，和上鎖的順序一致
		List<DailyQuote> sorted = quotes.stream().sorted(Comparator.comparing(DailyQuote::code)).toList();
		for (int attempt = 1;; attempt++) {
			try {
				return writeTransaction.execute(status -> upsertLocked(sorted));
			} catch (DataIntegrityViolationException e) {
				if (!isPrimaryKeyViolation(e) || attempt >= MAX_ATTEMPTS) {
					throw e;
				}
				log.info("寫入行情時別人剛好新增了同一個代號，整批重做一次");
			}
		}
	}

	private int upsertLocked(List<DailyQuote> sorted) {
		LocalDateTime now = LocalDateTime.now(clock);
		// 一次查回來並上鎖，不要每筆查一次資料庫
		Map<String, Quote> existing = new HashMap<>();
		quoteRepository.findAllByCodeForUpdate(sorted.stream().map(DailyQuote::code).distinct().toList())
				.forEach(quote -> existing.put(quote.getCode(), quote));

		List<Quote> created = new ArrayList<>();
		int changed = 0;
		for (DailyQuote incoming : sorted) {
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

	private static boolean isPrimaryKeyViolation(DataIntegrityViolationException e) {
		String message = e.getMostSpecificCause().getMessage();
		return message != null && message.toUpperCase(Locale.ROOT).contains(PRIMARY_KEY_CONSTRAINT);
	}
}
