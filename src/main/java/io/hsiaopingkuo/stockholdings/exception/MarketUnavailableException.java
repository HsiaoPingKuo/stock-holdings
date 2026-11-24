package io.hsiaopingkuo.stockholdings.exception;

/**
 * 官方網站連不上或回應異常，無法確認代號是上市還是上櫃（第 3 題）。
 * 和「查無此代號」分開：這不是使用者打錯，請他稍後再試即可。
 */
public class MarketUnavailableException extends RuntimeException {

	public MarketUnavailableException() {
		super("暫時無法連線官方網站確認代號，請稍後再試");
	}
}
