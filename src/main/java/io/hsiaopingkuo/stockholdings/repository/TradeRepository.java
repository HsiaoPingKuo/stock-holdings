package io.hsiaopingkuo.stockholdings.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import io.hsiaopingkuo.stockholdings.entity.Trade;

/** 交易的資料存取（第 6 題）。 */
public interface TradeRepository extends JpaRepository<Trade, Long> {

	// 某檔持股的全部交易，依重播順序（日期，同一天再依 id）；對應索引 idx_trade_holding_date
	List<Trade> findByHoldingIdOrderByTradeDateAscIdAsc(Long holdingId);

	// 清單用：一次查出全部交易，在記憶體裡依持股分組，不必每一檔查一次資料庫
	List<Trade> findAllByOrderByHoldingIdAscTradeDateAscIdAsc();
}
