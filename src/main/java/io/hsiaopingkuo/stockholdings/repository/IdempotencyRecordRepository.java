package io.hsiaopingkuo.stockholdings.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.hsiaopingkuo.stockholdings.entity.IdempotencyRecord;

/** 冪等鍵紀錄的資料存取（第 6 題）。 */
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

	Optional<IdempotencyRecord> findByIdempotencyKey(String idempotencyKey);

	/**
	 * 刪除早於 cutoff 建立的紀錄。用一句 DELETE，而不是先讀出來再一筆一筆刪：紀錄可能很多，不必全部載入記憶體。
	 *
	 * @return 刪了幾筆
	 */
	@Modifying
	@Query("delete from IdempotencyRecord r where r.createdAt < :cutoff")
	int deleteCreatedBefore(@Param("cutoff") LocalDateTime cutoff);
}
