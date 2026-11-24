package io.hsiaopingkuo.stockholdings.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import io.hsiaopingkuo.stockholdings.calc.TradeInput;
import io.hsiaopingkuo.stockholdings.calc.TradeSide;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 一筆買賣（第 6 題），對應 V4__create_trade.sql 的 trade 表。
 * 持股的股數、成本不再存在 holding，而是依日期重播這些交易算出來。
 */
@Entity
@Table(name = "trade")
public class Trade {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// 只存持股 id，不用 @ManyToOne：不必改 Holding，也不會有延遲載入的問題；外鍵由資料庫保護
	@Column(name = "holding_id", nullable = false)
	private Long holdingId;

	// 沿用計算核心的 enum，買賣方向只有一份定義；存名稱（BUY／SELL），對應 ck_trade_side
	@Enumerated(EnumType.STRING)
	@Column(name = "side", nullable = false, length = 4)
	private TradeSide side;

	@Column(name = "trade_date", nullable = false)
	private LocalDate tradeDate;

	@Column(name = "shares", nullable = false)
	private long shares;

	@Column(name = "price", nullable = false, precision = 12, scale = 2)
	private BigDecimal price;

	@Column(name = "date_estimated", nullable = false)
	private boolean dateEstimated;

	// 樂觀鎖：Hibernate 更新時自動比對並加 1，版本不一致代表別的裝置已經改過
	@Version
	@Column(name = "version", nullable = false)
	private long version;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	protected Trade() {
	}

	// 時間由呼叫端傳入（service 用台北時區的 Clock 算好），Entity 自己不碰系統時間
	public Trade(Long holdingId, TradeSide side, LocalDate tradeDate, long shares, BigDecimal price,
			boolean dateEstimated, LocalDateTime now) {
		this.holdingId = holdingId;
		this.side = side;
		this.tradeDate = tradeDate;
		this.shares = shares;
		this.price = price;
		this.dateEstimated = dateEstimated;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/**
	 * App 1.0 修改持股（第 6 題決定 3）：只改股數和價格，日期和「推估」標記不變。
	 * 版本號由 Hibernate 在寫回時自動加 1（@Version）。
	 */
	public void changeSharesAndPrice(long shares, BigDecimal price, LocalDateTime now) {
		this.shares = shares;
		this.price = price;
		this.updatedAt = now;
	}

	/**
	 * 2.0 修改交易：只改日期、股數、價格，買賣方向不能改。
	 * 推估標記只有在日期真的被改掉時才拿掉：只改價格時，日期仍然是推估的。
	 */
	public void changeDetails(LocalDate tradeDate, long shares, BigDecimal price, LocalDateTime now) {
		if (!this.tradeDate.equals(tradeDate)) {
			this.tradeDate = tradeDate;
			this.dateEstimated = false;
		}
		this.shares = shares;
		this.price = price;
		this.updatedAt = now;
	}

	/** 轉成計算核心用的 record（計算核心不依賴 Entity）。 */
	public TradeInput toInput() {
		return new TradeInput(id, tradeDate, side, shares, price);
	}

	public Long getId() {
		return id;
	}

	public Long getHoldingId() {
		return holdingId;
	}

	public TradeSide getSide() {
		return side;
	}

	public LocalDate getTradeDate() {
		return tradeDate;
	}

	public long getShares() {
		return shares;
	}

	public BigDecimal getPrice() {
		return price;
	}

	public boolean isDateEstimated() {
		return dateEstimated;
	}

	public long getVersion() {
		return version;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}
}
