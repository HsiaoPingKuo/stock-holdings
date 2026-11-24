package io.hsiaopingkuo.stockholdings.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 哪個市場、哪一年的除權息列表抓過了（第 6 題），對應 V5 的 dividend_fetch_range 表。
 * 有這一列 = 抓過（那一年真的沒有除權息也有一列），才分得出「真的沒有除權息」和「還沒抓」。
 */
@Entity
@Table(name = "dividend_fetch_range")
public class DividendFetchRange {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(name = "market", nullable = false, length = 4)
	private Market market;

	@Column(name = "fetch_year", nullable = false)
	private int fetchYear;

	@Column(name = "completed", nullable = false)
	private boolean completed;

	@Column(name = "last_fetched_at", nullable = false)
	private LocalDateTime lastFetchedAt;

	protected DividendFetchRange() {
	}

	public DividendFetchRange(Market market, int fetchYear, boolean completed, LocalDateTime now) {
		this.market = market;
		this.fetchYear = fetchYear;
		this.completed = completed;
		this.lastFetchedAt = now;
	}

	/**
	 * 又抓了一次。抓完的年份不會再抓，但保險起見：已經是抓完的，不會因為這次而變回沒抓完。
	 *
	 * @param completed 這次是不是在那一年結束之後才開始抓的
	 */
	public void markFetched(boolean completed, LocalDateTime now) {
		this.completed = this.completed || completed;
		this.lastFetchedAt = now;
	}

	public Long getId() {
		return id;
	}

	public Market getMarket() {
		return market;
	}

	public int getFetchYear() {
		return fetchYear;
	}

	public boolean isCompleted() {
		return completed;
	}

	public LocalDateTime getLastFetchedAt() {
		return lastFetchedAt;
	}
}
