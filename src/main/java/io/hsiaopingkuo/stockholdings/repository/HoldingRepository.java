package io.hsiaopingkuo.stockholdings.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.hsiaopingkuo.stockholdings.dto.HoldingQuoteRow;
import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.entity.Market;
import jakarta.persistence.LockModeType;

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
	 * 清單一列的查詢（清單頁、App API 共用）：持股 LEFT JOIN 行情快取，一個查詢就拿到持股和收盤價。
	 * LEFT JOIN：還沒有行情的持股也要列出來（收盤價顯示「未取得」）。
	 * 兩張表沒有設外鍵，所以用 on 自己指定用代號對應。
	 * 名稱、市場：持股自己沒有時（第 2 題存的舊資料）用行情快取的補上，App API 規格這兩個欄位不能是 null。
	 * 寫成常數：下面兩個查詢共用同一段，網頁和 API 的單筆、清單才不會各寫一份而對不起來。
	 * 第 6 題：股數、成本不再從這裡讀，由 HoldingService 另外查交易、重播後組成 HoldingListItem。
	 */
	String LIST_ITEM_QUERY = """
			select new io.hsiaopingkuo.stockholdings.dto.HoldingQuoteRow(
				h.id, h.code, coalesce(h.name, q.name), coalesce(h.market, q.market),
				q.closePrice, q.closeDate, q.latestTradeDate)
			from Holding h left join Quote q on q.code = h.code
			""";

	@Query(LIST_ITEM_QUERY + " order by h.code")
	List<HoldingQuoteRow> findAllForList();

	// 第 5 題：App API 的單筆查詢，以及新增、修改後回傳的持股物件
	@Query(LIST_ITEM_QUERY + " where h.id = :id")
	Optional<HoldingQuoteRow> findListItemById(@Param("id") Long id);

	/**
	 * 第 6 題：會改動交易的操作（App 1.0 修改、刪除、新增到既有持股），先鎖住這一列持股（SELECT … FOR UPDATE），
	 * 重播、檢查、寫入完才放開；同一檔持股的寫入因此一個一個來，兩支手機同時操作也不會超賣。
	 * 等不到鎖時（H2 預設等 2 秒）丟 PessimisticLockingFailureException，由 HoldingService 轉成「正在被其他裝置修改」。
	 * 必須在交易裡呼叫：交易結束時鎖才會放開。
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select h from Holding h where h.id = :id")
	Optional<Holding> findByIdForUpdate(@Param("id") Long id);

	// 同上，App 1.0 新增時用代號找既有的持股（決定 4：股數歸零後重新新增，要加在原本的持股下）
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select h from Holding h where h.code = :code")
	Optional<Holding> findByCodeForUpdate(@Param("code") String code);
}
