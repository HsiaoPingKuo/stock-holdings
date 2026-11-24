package io.hsiaopingkuo.stockholdings.quote;

import java.time.LocalDate;

/**
 * 查上市單一事件的除權息明細（每股現金股利、每千股配股）。
 * 包成 interface，背景排程的測試才能換成假的實作；主考官的測試伺服器可能沒有這支 API，呼叫端要有備援。
 */
public interface DividendDetailClient {

	/**
	 * @param stockNo 列表「詳細資料」欄位的代號
	 * @param exDate  列表「詳細資料」欄位的日期
	 * @return 不會丟例外：任何連線或解析問題都轉成 FAILED 或 BLOCKED
	 */
	DividendDetailResult fetchDetail(String stockNo, LocalDate exDate);
}
