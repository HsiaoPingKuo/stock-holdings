package io.hsiaopingkuo.stockholdings.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.hsiaopingkuo.stockholdings.entity.Quote;
import jakarta.persistence.LockModeType;

/** 行情快取的資料存取；主鍵是代號（字串）。 */
public interface QuoteRepository extends JpaRepository<Quote, String> {

	/**
	 * G-01：寫入行情前先鎖住已存在的列（SELECT … FOR UPDATE），交易結束才放開。
	 * 背景排程和新增持股的補查可能同時寫同一檔；不鎖的話，先讀到舊資料的一方最後提交，會把對方剛寫的新資料蓋掉。
	 * 依代號排序：每一方都照同樣的順序上鎖，兩批互相等對方的情況（死結）才不會發生。
	 * 等不到鎖時（H2 預設等 2 秒）丟 PessimisticLockingFailureException。必須在交易裡呼叫。
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select q from Quote q where q.code in :codes order by q.code")
	List<Quote> findAllByCodeForUpdate(@Param("codes") Collection<String> codes);
}
