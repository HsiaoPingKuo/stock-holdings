package io.hsiaopingkuo.stockholdings.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import io.hsiaopingkuo.stockholdings.entity.Holding;

/** 持股的資料存取；方法名稱照 Spring Data 的命名規則，SQL 會自動產生。 */
public interface HoldingRepository extends JpaRepository<Holding, Long> {

	// 新增前先檢查代號是否已存在，才能在表單上顯示友善的錯誤訊息
	boolean existsByCode(String code);

	// 清單依代號排序，同一個人每次看到的順序固定
	List<Holding> findAllByOrderByCodeAsc();
}
