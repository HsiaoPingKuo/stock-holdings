package io.hsiaopingkuo.stockholdings.exception;

/** 找不到指定 id 的交易（例如已在另一個裝置被刪掉）。API 回 404 NOT_FOUND，和找不到持股同一個代碼。 */
public class TradeNotFoundException extends RuntimeException {

	public TradeNotFoundException(Long id) {
		super("找不到這筆交易（id=" + id + "），可能已被刪除");
	}
}
