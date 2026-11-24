package io.hsiaopingkuo.stockholdings.config;

import java.time.Duration;
import java.time.LocalTime;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 外部行情 API 的設定（application.properties 的 quote.*）。
 * 主機網址可以用環境變數 TWSE_BASE_URL、TPEX_BASE_URL 換掉，主考官才能指到自己的測試伺服器；
 * 逾時一定要設，官方網站卡住時，使用者才不會一直等下去。
 *
 * @param twse           證交所主機
 * @param tpex           櫃買中心主機
 * @param connectTimeout 建立連線的上限
 * @param readTimeout    等待回應的上限
 * @param lookupTimeout        新增持股時「同時查兩個市場」的上限；實際上限還會再被剩下的預算縮短
 * @param lookupCacheTtl       代號查詢結果記住多久；輸入代號時的即時提示和按下新增共用，避免重複打官方
 * @param requestBudget        一個請求（新增持股、即時提示）的時間預算，排隊等名額、等官方都算在內（F-01）
 * @param maxConcurrentLookups 同時查官方的代號數上限；官方慢時不會讓大量請求一起卡住（F-01）
 * @param lookupMinRemaining   剩下的預算少於這個值就不查官方，直接回「暫時無法確認」（F-01）
 * @param holidayUrl           證交所休市日表的完整網址（主機是 openapi.twse.com.tw，和行情不同，所以獨立設定）
 * @param refresh              背景更新行情的排程設定
 * @param dividendReadTimeout  除權息 client 等待回應的上限：只在背景排程呼叫，一次查一整年實測約 5 秒，
 *                             行情的 readTimeout 不夠，所以另外設定；建立連線的上限沿用 connectTimeout
 */
@ConfigurationProperties("quote")
public record QuoteProperties(Host twse, Host tpex, Duration connectTimeout, Duration readTimeout,
		Duration lookupTimeout, Duration lookupCacheTtl, Duration requestBudget, int maxConcurrentLookups,
		Duration lookupMinRemaining, String holidayUrl, Refresh refresh, Duration dividendReadTimeout) {

	/** @param baseUrl 主機網址，例如 https://www.twse.com.tw；路徑和參數寫在 client 裡，不能改 */
	public record Host(String baseUrl) {
	}

	/**
	 * 背景更新行情（quote.refresh.*）。Spring 會自動對應環境變數，例如 QUOTE_REFRESH_ENABLED=false 可以關掉。
	 * 時間一律是台北時間。
	 *
	 * @param enabled             排程開關（測試時關掉，避免跑測試時發出請求）
	 * @param startTime           每個交易日開始查詢的時間（收盤 13:30、盤後最晚 13:33，官方約 13:50 後公布）
	 * @param slowdownTime        過了這個時間改成慢速（應付官方延遲公布）
	 * @param endTime             過了這個時間，只剩今天還沒拿到資料或還有待補查的市場繼續跑（改用延長時段的間隔）
	 * @param lateEndTime         延長時段結束的時間：過了就停到下一個平日（G-03）
	 * @param fastInterval        快速時段每一輪的間隔
	 * @param fastJitter          快速間隔的隨機加減範圍（避免每次都在整數秒送出）
	 * @param slowInterval        慢速時段，以及批次失敗改用逐檔時的間隔
	 * @param lateInterval        延長時段每一輪的間隔（G-03）
	 * @param perRequestDelayMin  逐檔查詢（和啟動時往前找）每個請求之間最少等多久
	 * @param perRequestDelayMax  每個請求之間最多等多久
	 * @param cooldown            被官方擋下後，這個市場暫停多久
	 * @param startupLookbackDays 伺服器啟動時，今天沒有資料最多往前找幾天
	 */
	public record Refresh(boolean enabled, LocalTime startTime, LocalTime slowdownTime, LocalTime endTime,
			LocalTime lateEndTime, Duration fastInterval, Duration fastJitter, Duration slowInterval,
			Duration lateInterval, Duration perRequestDelayMin, Duration perRequestDelayMax, Duration cooldown,
			int startupLookbackDays) {
	}
}
