package io.hsiaopingkuo.stockholdings.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.hsiaopingkuo.stockholdings.calc.DividendEventInput;
import io.hsiaopingkuo.stockholdings.calc.HoldingReplayCalculator;
import io.hsiaopingkuo.stockholdings.calc.HoldingReplayResult;
import io.hsiaopingkuo.stockholdings.calc.OversellException;
import io.hsiaopingkuo.stockholdings.calc.TradeInput;
import io.hsiaopingkuo.stockholdings.dto.HoldingListItem;
import io.hsiaopingkuo.stockholdings.dto.HoldingQuoteRow;

/**
 * 把「持股＋行情」一列、這檔的交易和除權息事件，重播後組成清單的一列（第 6 題）。
 * 清單和單筆查詢共用，兩邊的數字才不會對不起來。只做計算，不碰資料庫。
 */
final class HoldingAssembler {

	private static final Logger log = LoggerFactory.getLogger(HoldingAssembler.class);

	private HoldingAssembler() {
	}

	/**
	 * 重播時超賣不往外丟：寫入前都會檢查超賣，讀取時還發生代表資料被手動改過。
	 * 不能讓一檔壞掉的持股拖垮整個清單（變成 500），所以記 log、把這一檔標成「資料異常」，其他持股照常顯示。
	 */
	static HoldingListItem assemble(HoldingQuoteRow row, List<TradeInput> trades, List<DividendEventInput> events) {
		try {
			// 和畫面「未取得」同一個判斷：收盤價不能用時當成沒有收盤價，只算成本
			HoldingReplayResult replay = HoldingReplayCalculator.replay(row.code(), trades, events,
					row.hasClosePrice() ? row.closePrice() : null);
			return HoldingListItem.of(row, trades, replay);
		} catch (OversellException e) {
			// 只記訊息（哪一筆交易、當時持有幾股），不印 stack trace：原因是資料，不是程式
			log.warn("持股 {}（id={}）重播交易失敗，資料可能被手動修改：{}", row.code(), row.id(), e.getMessage());
			return HoldingListItem.dataError(row);
		}
	}
}
