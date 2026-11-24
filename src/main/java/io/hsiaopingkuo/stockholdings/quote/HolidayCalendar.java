package io.hsiaopingkuo.stockholdings.quote;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 判斷「今天是不是事先公布的休市日」，給排程決定今天要不要查行情。
 * 休市日表一天最多查一次（成功或失敗都記住當天的結果），不會每一輪排程都去查。
 * 查不到、格式不符、連不上，或表裡沒有今年的資料 → 一律當成交易日，排程照常，只寫 log。
 */
public class HolidayCalendar {

	private static final Logger log = LoggerFactory.getLogger(HolidayCalendar.class);

	private final HolidayScheduleClient client;
	private final Clock clock;

	// 上市、上櫃兩個排程可能同時來問，所以 isClosedToday 加 synchronized，這兩個欄位只在裡面讀寫
	private LocalDate fetchedOn;
	private Set<LocalDate> closedDates = Set.of();

	public HolidayCalendar(HolidayScheduleClient client, Clock clock) {
		this.client = client;
		this.clock = clock;
	}

	/** 今天（台北時間）是不是休市日。 */
	public synchronized boolean isClosedToday() {
		LocalDate today = LocalDate.now(clock);
		if (!today.equals(fetchedOn)) {
			refresh(today);
		}
		return closedDates.contains(today);
	}

	private void refresh(LocalDate today) {
		// 先記下今天已經查過：就算這次失敗，今天也不再重查
		fetchedOn = today;
		Optional<Set<LocalDate>> fetched;
		try {
			fetched = client.fetchClosedDates();
		} catch (RuntimeException e) {
			// client 約定不丟例外；萬一丟了也不能影響排程
			log.warn("休市日表查詢發生例外，今天當成交易日", e);
			fetched = Optional.empty();
		}
		if (fetched.isEmpty()) {
			log.warn("休市日表查不到，今天（{}）當成交易日照常排程", today);
			closedDates = Set.of();
			return;
		}
		closedDates = Set.copyOf(fetched.get());
		boolean coversThisYear = closedDates.stream().anyMatch(date -> date.getYear() == today.getYear());
		if (!coversThisYear) {
			// 實測：年底時官方只提供明年的表，今年剩下的日子無從判斷
			log.info("休市日表沒有 {} 年的資料，今天當成交易日照常排程", today.getYear());
		} else {
			log.info("休市日表已更新：今天（{}）{}", today, closedDates.contains(today) ? "休市" : "不是休市日");
		}
	}
}
