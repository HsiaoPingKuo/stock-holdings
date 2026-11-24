package io.hsiaopingkuo.stockholdings.exception;

/**
 * 重播這筆持股的交易時發生超賣（第 6 題）。正常操作不會發生（寫入前都會檢查），
 * 只有資料被手動改過才會出現；股數、損益都算不出來，所以不讓 App 1.0 讀取或修改。
 * 訊息是 /api/v1 的 409 DATA_ERROR；網頁另外顯示自己的訊息（見 HoldingController）。
 */
public class HoldingDataException extends RuntimeException {

	public HoldingDataException() {
		super("這筆持股的交易資料異常，請使用網頁檢查");
	}
}
