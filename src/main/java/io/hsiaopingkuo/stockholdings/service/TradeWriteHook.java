package io.hsiaopingkuo.stockholdings.service;

import io.hsiaopingkuo.stockholdings.entity.Trade;

/**
 * TradeService 每一段「會寫入交易的資料庫交易」的前後各呼叫一次（第 6 題）。
 * 冪等鍵用它把自己的紀錄放進同一個資料庫交易：寫入失敗時一起回滾，成功時一起 commit。
 * 交易範圍由 TradeService 決定（新增時查官方要在交易外，F-01），冪等鍵只是借用。
 */
public interface TradeWriteHook {

	/** 不需要冪等鍵時（App 1.0、5a 的呼叫方式）用這個：什麼都不做。 */
	TradeWriteHook NONE = new TradeWriteHook() {
		@Override
		public void beforeLock() {
		}

		@Override
		public void afterWrite(Trade trade) {
		}
	};

	/**
	 * 資料庫交易一開始、鎖持股之前。順序固定（先冪等鍵、再持股），兩個請求才不會各拿一把、互相等待。
	 * 同一段寫入失敗重試時（新增全新代號遇到 UNIQUE），每一段新的資料庫交易都會再呼叫一次。
	 */
	void beforeLock();

	/**
	 * 寫入完成、commit 之前。
	 *
	 * @param trade 新增或修改後的交易；刪除時是被刪掉的那一筆
	 */
	void afterWrite(Trade trade);
}
