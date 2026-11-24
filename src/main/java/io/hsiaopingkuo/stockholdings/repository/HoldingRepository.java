package io.hsiaopingkuo.stockholdings.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.hsiaopingkuo.stockholdings.dto.HoldingListItem;
import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.entity.Market;

/** 持股的資料存取；方法名稱照 Spring Data 的命名規則，SQL 會自動產生。 */
public interface HoldingRepository extends JpaRepository<Holding, Long> {

	// 新增前先檢查代號是否已存在，才能在表單上顯示友善的錯誤訊息
	boolean existsByCode(String code);

	// 清單依代號排序，同一個人每次看到的順序固定
	List<Holding> findAllByOrderByCodeAsc();

	// 第 3 題：批次失敗改用逐檔時，只查這個市場「有人持有」的代號
	List<Holding> findByMarketOrderByCodeAsc(Market market);

	// 第 3 題：第 2 題時存的舊持股沒有市場，背景補查
	List<Holding> findByMarketIsNullOrderByCodeAsc();

	/**
	 * 補上舊持股的市場和名稱。用一句 UPDATE，而不是「讀出來、改欄位、再 save」：
	 * 補查時使用者可能剛好把這筆刪掉，save 一個已刪除的物件有可能把它寫回去；UPDATE 找不到那一列就只是更新 0 筆。
	 * 條件 market is null：已經有市場的不覆蓋；名稱也只在原本是空的時候才填。
	 *
	 * @return 更新了幾筆（0 或 1）
	 */
	// UPDATE 直接改資料庫、不經過 JPA 快取：執行前先把待寫入的變更送出，執行後清掉快取，之後讀到的才是新值
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Transactional
	@Query("""
			update Holding h
			set h.market = :market, h.name = coalesce(h.name, :name), h.updatedAt = :now
			where h.id = :id and h.market is null
			""")
	int fillMarket(@Param("id") Long id, @Param("market") Market market, @Param("name") String name,
			@Param("now") LocalDateTime now);

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
