package io.hsiaopingkuo.stockholdings.exception;

/**
 * 這筆持股不能用 App 1.0 的方式修改、刪除（第 6 題決定 3）：有多筆交易、有零股，或配過股。
 * 訊息是 /api/v1 的 409 UPGRADE_REQUIRED；網頁另外顯示自己的訊息（見 HoldingController）。
 */
public class UpgradeRequiredException extends RuntimeException {

	public UpgradeRequiredException() {
		super("這筆持股有多筆交易或配股，請使用網頁或 App 2.0 修改");
	}
}
