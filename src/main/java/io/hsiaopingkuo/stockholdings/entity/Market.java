package io.hsiaopingkuo.stockholdings.entity;

/**
 * 股票掛牌的市場。資料庫存 enum 名稱（TWSE／TPEX，剛好 4 個字，對應 V2 的 VARCHAR(4)），
 * 畫面顯示台灣使用者習慣的「上市／上櫃」。
 */
public enum Market {

	/** 上市：台灣證券交易所。 */
	TWSE("上市"),
	/** 上櫃：證券櫃檯買賣中心。 */
	TPEX("上櫃");

	private final String label;

	Market(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}
}
