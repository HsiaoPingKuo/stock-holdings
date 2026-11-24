package io.hsiaopingkuo.stockholdings.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.hsiaopingkuo.stockholdings.entity.DividendDetailStatus;
import io.hsiaopingkuo.stockholdings.entity.DividendEvent;
import io.hsiaopingkuo.stockholdings.entity.Market;

/** 除權息事件的資料存取（第 6 題）。 */
public interface DividendEventRepository extends JpaRepository<DividendEvent, Long> {

	// 重播用：一次查完清單上全部代號的事件，依代號、除權息日排序；對應索引 idx_dividend_event_code_date
	List<DividendEvent> findByCodeInOrderByCodeAscExDateAscIdAsc(Collection<String> codes);

	// 寫入列表時：一次讀出這段期間已有的事件，在記憶體裡比對，不必每一列查一次
	List<DividendEvent> findByMarketAndExDateBetween(Market market, LocalDate start, LocalDate end);

	/**
	 * 要查明細的事件：這些代號裡「待查」的，加上「失敗、而且今天還沒查過」的（同一天不重查）。
	 * 「除權息日晚於那檔第一筆交易日」由 service 再篩，因為每檔的日期不同。
	 */
	@Query("""
			select e from DividendEvent e
			where e.market = :market and e.code in :codes
			  and (e.detailStatus = :pending
			       or (e.detailStatus = :failed and e.detailCheckedAt < :startOfToday))
			order by e.exDate, e.code
			""")
	List<DividendEvent> findDetailCandidates(@Param("market") Market market, @Param("codes") Collection<String> codes,
			@Param("pending") DividendDetailStatus pending, @Param("failed") DividendDetailStatus failed,
			@Param("startOfToday") LocalDateTime startOfToday);

	// 股利資料狀態用：這些代號裡明細還在某個狀態（待查）的事件
	List<DividendEvent> findByDetailStatusAndCodeIn(DividendDetailStatus detailStatus, Collection<String> codes);
}
