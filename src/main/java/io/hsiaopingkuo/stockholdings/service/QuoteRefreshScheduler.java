package io.hsiaopingkuo.stockholdings.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;

import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.quote.HolidayCalendar;
import io.hsiaopingkuo.stockholdings.service.QuoteRefreshService.Trigger;

/**
 * 背景更新行情的排程：上市、上櫃各一條獨立的鏈。
 *
 * 不用固定的 cron，而是「每一輪做完，才算出下一輪的時間再排進去」：
 * 同一個市場永遠只有一輪在跑，不會因為逐檔查詢比較久而兩輪重疊；
 * 間隔也能依狀態調整（快速／慢速／冷卻／今天已完成）。
 *
 * 伺服器啟動完成（ApplicationReadyEvent）時，兩個市場各排一個立即執行的「啟動輪」，
 * 在背景執行緒跑，不會拖慢啟動；官方連不上也只會寫 log。
 */
public class QuoteRefreshScheduler implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(QuoteRefreshScheduler.class);

	private final QuoteRefreshService refreshService;
	private final HolidayCalendar holidayCalendar;
	private final RefreshSchedule schedule;
	private final TaskScheduler taskScheduler;
	private final Clock clock;
	private final boolean enabled;

	public QuoteRefreshScheduler(QuoteRefreshService refreshService, HolidayCalendar holidayCalendar,
			RefreshSchedule schedule, TaskScheduler taskScheduler, Clock clock, boolean enabled) {
		this.refreshService = refreshService;
		this.holidayCalendar = holidayCalendar;
		this.schedule = schedule;
		this.taskScheduler = taskScheduler;
		this.clock = clock;
		this.enabled = enabled;
	}

	/** 伺服器啟動完成後才開始：這時候資料庫和所有元件都準備好了。 */
	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() {
		if (!enabled) {
			log.info("背景更新行情已關閉（quote.refresh.enabled=false）");
			return;
		}
		for (Market market : Market.values()) {
			// 排進背景執行緒後立刻返回，啟動流程不用等官方回應
			taskScheduler.schedule(() -> runStartupRound(market), clock.instant());
		}
	}

	/** 啟動輪：不看時段和休市日，一定查一次（往前找最近一個交易日）。 */
	void runStartupRound(Market market) {
		try {
			refreshService.runRound(market, Trigger.STARTUP);
		} catch (RuntimeException e) {
			// runRound 約定不丟例外；萬一丟了也要排下一輪，排程不能就此停掉
			log.warn("{} 啟動時查詢行情發生例外", market, e);
		} finally {
			scheduleNext(market);
		}
	}

	/** 排程輪：先確認現在該不該跑、今天是不是休市日，才真的查。 */
	void runScheduledRound(Market market) {
		try {
			MarketRefreshState state = refreshService.state(market);
			ZonedDateTime now = ZonedDateTime.now(clock);
			if (!schedule.shouldRun(now, state)) {
				return;
			}
			if (holidayCalendar.isClosedToday()) {
				// 事先公布的休市日：今天不查，直接當成完成，明天 13:40 再開始
				LocalDate today = now.toLocalDate();
				state.markDone(today);
				log.info("{} 今天（{}）是休市日，不查詢行情", market, today);
				return;
			}
			refreshService.runRound(market, Trigger.SCHEDULED);
		} catch (RuntimeException e) {
			log.warn("{} 排程查詢行情發生例外", market, e);
		} finally {
			scheduleNext(market);
		}
	}

	private void scheduleNext(Market market) {
		try {
			ZonedDateTime next = schedule.nextRun(ZonedDateTime.now(clock), refreshService.state(market));
			taskScheduler.schedule(() -> runScheduledRound(market), next.toInstant());
			log.info("{} 下一次查詢行情：{}", market, next.toLocalDateTime());
		} catch (RuntimeException e) {
			// 伺服器關閉中，排程器已停止時會拒絕新工作，這是正常的
			log.info("{} 無法排下一次查詢（伺服器可能正在關閉）：{}", market, e.toString());
		}
	}

	/** 伺服器關閉時停止排程執行緒；正在等待的逐檔查詢會被中斷並結束。 */
	@Override
	public void close() throws Exception {
		if (taskScheduler instanceof DisposableBean disposable) {
			disposable.destroy();
		}
	}
}
