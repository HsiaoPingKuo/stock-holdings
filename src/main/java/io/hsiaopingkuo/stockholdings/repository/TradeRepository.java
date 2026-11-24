package io.hsiaopingkuo.stockholdings.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import io.hsiaopingkuo.stockholdings.dto.CodeFirstTradeDate;
import io.hsiaopingkuo.stockholdings.entity.Trade;

/** 交易的資料存取（第 6 題）。 */
public interface TradeRepository extends JpaRepository<Trade, Long> {

	// 某檔持股的全部交易，依重播順序（日期，同一天再依 id）；對應索引 idx_trade_holding_date
	List<Trade> findByHoldingIdOrderByTradeDateAscIdAsc(Long holdingId);

	// 清單用：一次查出全部交易，在記憶體裡依持股分組，不必每一檔查一次資料庫
	List<Trade> findAllByOrderByHoldingIdAscTradeDateAscIdAsc();

	// 除權息同步：從所有交易最早的那一年開始抓；沒有任何交易時是空的（不抓）
	@Query("select min(t.tradeDate) from Trade t")
	Optional<LocalDate> findEarliestTradeDate();

	/**
	 * 除權息同步：holding 表每個代號（含已賣光的）的第一筆交易日。
	 * 兩張表沒有設 JPA 關聯，所以用 on 自己指定用 holding_id 對應；沒有交易的持股不會出現（沒有持有期間）。
	 */
	@Query("""
			select new io.hsiaopingkuo.stockholdings.dto.CodeFirstTradeDate(h.code, min(t.tradeDate))
			from Holding h join Trade t on t.holdingId = h.id
			group by h.code
			""")
	List<CodeFirstTradeDate> findFirstTradeDateByCode();
}
