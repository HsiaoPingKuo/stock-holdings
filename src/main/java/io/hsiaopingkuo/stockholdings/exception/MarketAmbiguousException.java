package io.hsiaopingkuo.stockholdings.exception;

/**
 * 證交所和櫃買都查到這個代號，最近交易日又相同，無法判斷是上市還是上櫃（第 3 題，Wynn 檢查報告 F-03）。
 * 和 MarketUnavailableException（連不上）分開：這種情況重試也不會好，要人工追查；
 * 之後 App API 也才能回不同的錯誤代碼。
 */
public class MarketAmbiguousException extends RuntimeException {

	private final String code;

	public MarketAmbiguousException(String code) {
		super("無法判斷 " + code + " 是上市或上櫃，請聯絡管理者");
		this.code = code;
	}

	public String getCode() {
		return code;
	}
}
