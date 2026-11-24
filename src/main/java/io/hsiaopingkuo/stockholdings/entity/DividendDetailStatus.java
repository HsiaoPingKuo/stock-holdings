package io.hsiaopingkuo.stockholdings.entity;

/**
 * 上市除權息明細（TWT49UDetail）的查詢狀態（第 6 題），存在 dividend_event.detail_status。
 * 只有上市、而且用列表算不出官方值的事件才需要查明細。
 */
public enum DividendDetailStatus {
	/** 不需要查：上櫃（列表本身就有官方值），或上市用列表就算得出官方值（例如只除息）。 */
	NOT_NEEDED,
	/** 待查：用列表算不出官方值，還沒查過明細。 */
	PENDING,
	/** 查到明細：採用明細的官方值。之後重抓列表也保留。 */
	FOUND,
	/** 官方回覆沒有這筆明細（或列表沒有明細參數）：不再查，保留列表算出來的估算或無法取得。 */
	NOT_FOUND,
	/** 連線、格式、HTTP 錯誤：同一天不重查，隔天再試。 */
	FAILED
}
