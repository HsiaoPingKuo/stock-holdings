package io.hsiaopingkuo.stockholdings.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import io.hsiaopingkuo.stockholdings.dto.HoldingListItem;
import io.hsiaopingkuo.stockholdings.entity.Holding;

/** 持股的資料存取；方法名稱照 Spring Data 的命名規則，SQL 會自動產生。 */
public interface HoldingRepository extends JpaRepository<Holding, Long> {

	// 新增前先檢查代號是否已存在，才能在表單上顯示友善的錯誤訊息
	boolean existsByCode(String code);

	// 清單依代號排序，同一個人每次看到的順序固定
	List<Holding> findAllByOrderByCodeAsc();

	/**
	 * 清單頁用：持股 LEFT JOIN 行情快取，一個查詢就拿到全部要顯示的欄位。
	 * LEFT JOIN：還沒有行情的持股也要列出來（收盤價顯示「未取得」）。
	 * 兩張表沒有設外鍵，所以用 on 自己指定用代號對應。
	 */
	@Query("""
			select new io.hsiaopingkuo.stockholdings.dto.HoldingListItem(
				h.id, h.code, coalesce(h.name, q.name), h.market, h.lots, h.buyPrice,
				q.closePrice, q.closeDate, q.latestTradeDate)
			from Holding h left join Quote q on q.code = h.code
			order by h.code
			""")
	List<HoldingListItem> findAllForList();
}
