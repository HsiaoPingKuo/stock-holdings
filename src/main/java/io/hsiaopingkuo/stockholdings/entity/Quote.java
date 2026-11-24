package io.hsiaopingkuo.stockholdings.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

import io.hsiaopingkuo.stockholdings.quote.DailyQuote;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 一檔股票的行情快取，對應 V3__create_quote.sql 的 quote 表。
 * 「不用舊資料蓋掉新資料」的規則寫在 {@link #apply}，所有寫入都經過這裡，規則只有一份。
 */
@Entity
@Table(name = "quote")
public class Quote {

	// 代號本身就是主鍵（一個代號一列），不用流水號
	@Id
	@Column(name = "code", length = 10)
	private String code;

	@Column(name = "name", length = 50)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(name = "market", nullable = false, length = 4)
	private Market market;

	@Column(name = "close_price", precision = 12, scale = 2)
	private BigDecimal closePrice;

	@Column(name = "close_date")
	private LocalDate closeDate;

	@Column(name = "latest_trade_date", nullable = false)
	private LocalDate latestTradeDate;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	protected Quote() {
	}

	/** 第一次看到這個代號：先放交易日，其餘欄位交給 apply 填，規則才只寫一次。 */
	public Quote(DailyQuote first, LocalDateTime now) {
		this.code = first.code();
		this.market = first.market();
		this.latestTradeDate = first.tradeDate();
		this.updatedAt = now;
		apply(first, now);
	}

	/**
	 * 用新拿到的資料更新這一列。兩個日期各自比較，只接受「不比現在舊」的資料：
	 * - 交易日不比 latest_trade_date 舊 → 更新最近交易日、名稱、市場
	 * - 有收盤價，而且收盤日不比 close_date 舊 → 更新收盤價和收盤日
	 * 當天沒成交（"--"）的資料只會推進最近交易日，原本的收盤價保留下來。
	 *
	 * @return 有沒有任何欄位真的變了（給 log 統計更新筆數）
	 */
	public boolean apply(DailyQuote incoming, LocalDateTime now) {
		boolean changed = false;

		if (!incoming.tradeDate().isBefore(latestTradeDate)) {
			// 名稱是 null 代表這份資料不知道名稱，不要把原本有的名稱洗掉
			String newName = incoming.name() != null ? incoming.name() : name;
			changed = !incoming.tradeDate().equals(latestTradeDate) || !Objects.equals(newName, name)
					|| incoming.market() != market;
			latestTradeDate = incoming.tradeDate();
			name = newName;
			market = incoming.market();
		}

		if (incoming.closePrice() != null && incoming.closeDate() != null
				&& (closeDate == null || !incoming.closeDate().isBefore(closeDate))) {
			// 金額用 compareTo 比：2030.0 和 2030.00 在 BigDecimal.equals 裡算不同
			boolean priceChanged = closePrice == null || closePrice.compareTo(incoming.closePrice()) != 0;
			changed = changed || priceChanged || !incoming.closeDate().equals(closeDate);
			closePrice = incoming.closePrice();
			closeDate = incoming.closeDate();
		}

		if (changed) {
			updatedAt = now;
		}
		return changed;
	}

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public Market getMarket() {
		return market;
	}

	public BigDecimal getClosePrice() {
		return closePrice;
	}

	public LocalDate getCloseDate() {
		return closeDate;
	}

	public LocalDate getLatestTradeDate() {
		return latestTradeDate;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}
}
