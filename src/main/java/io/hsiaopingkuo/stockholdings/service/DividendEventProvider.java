package io.hsiaopingkuo.stockholdings.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import io.hsiaopingkuo.stockholdings.calc.DividendEventInput;

/**
 * 重播交易時要用的除權息事件（第 6 題）。
 * 第 3 階段的實作一律回傳空的（NoDividendEventProvider）；第 4 階段改成讀 dividend_event 表。
 *
 * 規則（實作都要遵守）：
 * - 只讀資料庫，不可以呼叫官方 API：寫入時會在鎖住持股的交易裡呼叫，等官方會讓其他裝置一直等不到鎖（F-01）。
 * - 一次查多個代號：清單只呼叫一次，持股再多也不會變成每檔查一次。
 */
public interface DividendEventProvider {

	/**
	 * @param codes 要查的股票代號
	 * @return 代號 → 這檔的除權息事件；沒有事件的代號可以不出現在 Map 裡
	 */
	Map<String, List<DividendEventInput>> eventsByCode(Collection<String> codes);
}
