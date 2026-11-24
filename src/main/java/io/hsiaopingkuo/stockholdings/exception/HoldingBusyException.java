package io.hsiaopingkuo.stockholdings.exception;

/**
 * 等不到這筆持股的鎖（第 6 題設計決定 6-B）：別的裝置正在修改同一檔持股。
 * /api/v1 回 409 HOLDING_BUSY，網頁顯示同一句，都不能變成 500。
 */
public class HoldingBusyException extends RuntimeException {

	public HoldingBusyException(Throwable cause) {
		super("這筆持股正在被其他裝置修改，請稍後再試", cause);
	}
}
