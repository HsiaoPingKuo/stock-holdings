package io.hsiaopingkuo.stockholdings.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import io.hsiaopingkuo.stockholdings.entity.DividendFetchRange;
import io.hsiaopingkuo.stockholdings.entity.Market;

/** 除權息列表已抓範圍的資料存取（第 6 題）。 */
public interface DividendFetchRangeRepository extends JpaRepository<DividendFetchRange, Long> {

	// 決定這一輪要抓哪幾年
	List<DividendFetchRange> findByMarket(Market market);

	// 寫入時找這一年的紀錄：有就更新，沒有就新增
	Optional<DividendFetchRange> findByMarketAndFetchYear(Market market, int fetchYear);
}
