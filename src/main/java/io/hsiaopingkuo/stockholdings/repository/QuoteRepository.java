package io.hsiaopingkuo.stockholdings.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import io.hsiaopingkuo.stockholdings.entity.Quote;

/** 行情快取的資料存取；主鍵是代號（字串）。 */
public interface QuoteRepository extends JpaRepository<Quote, String> {
}
