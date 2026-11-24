package io.hsiaopingkuo.stockholdings.exception;

/** 證交所、櫃買都確定沒有這個代號（第 3 題）。 */
public class StockCodeNotFoundException extends RuntimeException {

	private final String code;

	public StockCodeNotFoundException(String code) {
		super("查無此股票代號：" + code + "（上市、上櫃都找不到）");
		this.code = code;
	}

	public String getCode() {
		return code;
	}
}
