package io.hsiaopingkuo.stockholdings.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

import io.hsiaopingkuo.stockholdings.calc.DividendEventInput;
import io.hsiaopingkuo.stockholdings.calc.DividendKind;
import io.hsiaopingkuo.stockholdings.calc.DividendSource;
import io.hsiaopingkuo.stockholdings.calc.DividendSourceRule;
import io.hsiaopingkuo.stockholdings.calc.ResolvedDividend;
import io.hsiaopingkuo.stockholdings.quote.DividendDetailResult;
import io.hsiaopingkuo.stockholdings.quote.DividendListRow;
import io.hsiaopingkuo.stockholdings.quote.TwseDividendDetail;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 一次除權息（第 6 題），對應 V5__create_dividend_event.sql 的 dividend_event 表。
 * 「用列表更新」「存明細結果」「重新決定採用的值」的規則都寫在這裡，所有寫入都經過這些方法，規則只有一份
 * （和 Quote.apply 的做法一樣）。
 */
@Entity
@Table(name = "dividend_event")
public class DividendEvent {

	private static final int AMOUNT_PRECISION = 18;
	private static final int AMOUNT_SCALE = 8;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(name = "market", nullable = false, length = 4)
	private Market market;

	@Column(name = "code", nullable = false, length = 10)
	private String code;

	@Column(name = "name", length = 50)
	private String name;

	@Column(name = "ex_date", nullable = false)
	private LocalDate exDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "kind", nullable = false, length = 14)
	private DividendKind kind;

	// ===== 列表的原始值 =====

	@Column(name = "prev_close", precision = AMOUNT_PRECISION, scale = AMOUNT_SCALE)
	private BigDecimal prevClose;

	@Column(name = "ex_right_reference", precision = AMOUNT_PRECISION, scale = AMOUNT_SCALE)
	private BigDecimal exRightReference;

	@Column(name = "rights_plus_dividend", precision = AMOUNT_PRECISION, scale = AMOUNT_SCALE)
	private BigDecimal rightsPlusDividend;

	@Column(name = "dividend_reference", precision = AMOUNT_PRECISION, scale = AMOUNT_SCALE)
	private BigDecimal dividendReference;

	@Column(name = "listed_cash_per_share", precision = AMOUNT_PRECISION, scale = AMOUNT_SCALE)
	private BigDecimal listedCashPerShare;

	@Column(name = "listed_stock_per_thousand", precision = AMOUNT_PRECISION, scale = AMOUNT_SCALE)
	private BigDecimal listedStockPerThousand;

	@Column(name = "detail_stock_no", length = 10)
	private String detailStockNo;

	@Column(name = "detail_date")
	private LocalDate detailDate;

	// ===== 上市明細的結果 =====

	@Enumerated(EnumType.STRING)
	@Column(name = "detail_status", nullable = false, length = 10)
	private DividendDetailStatus detailStatus;

	@Column(name = "detail_cash_per_share", precision = AMOUNT_PRECISION, scale = AMOUNT_SCALE)
	private BigDecimal detailCashPerShare;

	@Column(name = "detail_stock_per_thousand", precision = AMOUNT_PRECISION, scale = AMOUNT_SCALE)
	private BigDecimal detailStockPerThousand;

	@Column(name = "detail_checked_at")
	private LocalDateTime detailCheckedAt;

	// ===== 重播時採用的值 =====

	@Column(name = "cash_per_share", precision = AMOUNT_PRECISION, scale = AMOUNT_SCALE)
	private BigDecimal cashPerShare;

	@Column(name = "stock_per_thousand", precision = AMOUNT_PRECISION, scale = AMOUNT_SCALE)
	private BigDecimal stockPerThousand;

	@Enumerated(EnumType.STRING)
	@Column(name = "source", nullable = false, length = 11)
	private DividendSource source;

	@Column(name = "list_fetched_at", nullable = false)
	private LocalDateTime listFetchedAt;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	protected DividendEvent() {
	}

	/**
	 * 第一次在列表看到這次除權息。市場、代號、日期是唯一鍵，之後不會再改；其餘欄位交給 apply 填，規則才只寫一次。
	 *
	 * @param ambiguous 同一份列表裡，這一檔這一天出現不只一列（見 {@link #applyAmbiguousListRow}）
	 */
	public DividendEvent(DividendListRow row, boolean ambiguous, LocalDateTime now) {
		this.market = row.market();
		this.code = row.code();
		this.exDate = row.exDate();
		this.createdAt = now;
		apply(row, ambiguous, now);
	}

	/**
	 * 重抓列表時更新這一筆：列表原始值換成新的；已經查到（FOUND）或確定查不到（NOT_FOUND）的明細保留，
	 * 重抓今年才不會把查過的明細清掉。
	 *
	 * @return 有沒有任何欄位真的變了（給 log 統計更新筆數）
	 */
	public boolean applyListRow(DividendListRow row, LocalDateTime now) {
		return apply(row, false, now);
	}

	/**
	 * 同一份列表裡，這一檔這一天出現不只一列（實測沒有，設計決定 7-B）：
	 * 兩列都不一定對，不猜數字，存成「無法取得」。原始值存第一列的，方便除錯。
	 */
	public boolean applyAmbiguousListRow(DividendListRow firstRow, LocalDateTime now) {
		return apply(firstRow, true, now);
	}

	private boolean apply(DividendListRow row, boolean ambiguous, LocalDateTime now) {
		if (row.market() != market || !row.code().equals(code) || !row.exDate().equals(exDate)) {
			throw new IllegalArgumentException("列表的這一列不是同一次除權息：" + row.code() + " " + row.exDate());
		}
		Snapshot before = snapshot();

		name = row.name();
		kind = row.kind();
		prevClose = row.prevClose();
		exRightReference = row.exRightReference();
		rightsPlusDividend = row.rightsPlusDividend();
		dividendReference = row.dividendReference();
		listedCashPerShare = row.listedCashPerShare();
		listedStockPerThousand = row.listedStockPerThousand();
		detailStockNo = row.detailStockNo();
		detailDate = row.detailDate();
		listFetchedAt = now;

		if (ambiguous) {
			// 查明細也分不出是哪一列，所以不用查；FOUND、NOT_FOUND 是查過的紀錄，留著
			if (detailStatus != DividendDetailStatus.FOUND && detailStatus != DividendDetailStatus.NOT_FOUND) {
				detailStatus = DividendDetailStatus.NOT_NEEDED;
			}
			setResolved(new ResolvedDividend(null, null, DividendSource.UNAVAILABLE));
		} else {
			detailStatus = detailStatusAfterList(row);
			resolve();
		}

		boolean changed = !before.equals(snapshot());
		if (changed || updatedAt == null) {
			updatedAt = now;
		}
		return changed;
	}

	/** 重抓列表後，明細要不要查（設計決定 7-B、第 4b 階段提案第 3 節）。 */
	private DividendDetailStatus detailStatusAfterList(DividendListRow row) {
		// 查過的結果保留：FOUND 是官方的值；NOT_FOUND 是官方說沒有，不再查
		if (detailStatus == DividendDetailStatus.FOUND || detailStatus == DividendDetailStatus.NOT_FOUND) {
			return detailStatus;
		}
		if (market == Market.TPEX || DividendSourceRule.resolve(row, null).source() == DividendSource.OFFICIAL) {
			return DividendDetailStatus.NOT_NEEDED;
		}
		// 沒有「詳細資料」參數就沒辦法查明細，等同查不到
		if (row.detailStockNo() == null || row.detailDate() == null) {
			return DividendDetailStatus.NOT_FOUND;
		}
		// 原本是 FAILED 的維持 FAILED，才不會因為重抓列表而在同一天重查
		return detailStatus == DividendDetailStatus.FAILED ? DividendDetailStatus.FAILED : DividendDetailStatus.PENDING;
	}

	/**
	 * 存一次明細查詢的結果，並重新決定採用的值。被擋（BLOCKED）不算查過，由呼叫端處理，不可以傳進來。
	 */
	public void applyDetailResult(DividendDetailResult result, LocalDateTime now) {
		switch (result.status()) {
			case FOUND -> {
				TwseDividendDetail detail = result.detail();
				if (detail == null || detail.cashPerShare() == null || detail.stockPerThousand() == null) {
					// client 保證查到時兩個數字都有；萬一沒有，當成失敗、隔天再試，不存不完整的明細
					detailStatus = DividendDetailStatus.FAILED;
				} else {
					detailStatus = DividendDetailStatus.FOUND;
					detailCashPerShare = detail.cashPerShare();
					detailStockPerThousand = detail.stockPerThousand();
				}
			}
			case NOT_FOUND -> detailStatus = DividendDetailStatus.NOT_FOUND;
			case FAILED -> detailStatus = DividendDetailStatus.FAILED;
			case BLOCKED -> throw new IllegalArgumentException("被擋不算查過明細，不可以存：" + code + " " + exDate);
		}
		detailCheckedAt = now;
		resolve();
		updatedAt = now;
	}

	/** 依 DividendSourceRule 重新決定採用的值：有明細用明細，沒有就用列表判斷。 */
	private void resolve() {
		TwseDividendDetail detail = detailStatus == DividendDetailStatus.FOUND
				? new TwseDividendDetail(code, detailCashPerShare, detailStockPerThousand)
				: null;
		setResolved(DividendSourceRule.resolve(toListRow(), detail));
	}

	private void setResolved(ResolvedDividend resolved) {
		cashPerShare = resolved.cashPerShare();
		stockPerThousand = resolved.stockPerThousand();
		source = resolved.source();
	}

	/** 用存著的原始值還原成列表的一列，重新判斷時不必再打官方。 */
	private DividendListRow toListRow() {
		return new DividendListRow(market, exDate, code, name, kind, prevClose, exRightReference, rightsPlusDividend,
				dividendReference, listedCashPerShare, listedStockPerThousand, detailStockNo, detailDate);
	}

	/** 轉成重播用的 record。「無法取得」的也要給，重播才會列進明細。 */
	public DividendEventInput toInput() {
		return new DividendEventInput(exDate, kind, cashPerShare, stockPerThousand, source);
	}

	/**
	 * 判斷「有沒有變」用的快照。金額先 stripTrailingZeros：從資料庫讀回來的是 8 位小數（4.50002000），
	 * 列表解析出來的是 4.500020，數值相同但 equals 會判成不同。
	 */
	private Snapshot snapshot() {
		return new Snapshot(name, kind, strip(prevClose), strip(exRightReference), strip(rightsPlusDividend),
				strip(dividendReference), strip(listedCashPerShare), strip(listedStockPerThousand), detailStockNo,
				detailDate, detailStatus, strip(cashPerShare), strip(stockPerThousand), source);
	}

	private static BigDecimal strip(BigDecimal value) {
		return value == null ? null : value.stripTrailingZeros();
	}

	private record Snapshot(String name, DividendKind kind, BigDecimal prevClose, BigDecimal exRightReference,
			BigDecimal rightsPlusDividend, BigDecimal dividendReference, BigDecimal listedCashPerShare,
			BigDecimal listedStockPerThousand, String detailStockNo, LocalDate detailDate,
			DividendDetailStatus detailStatus, BigDecimal cashPerShare, BigDecimal stockPerThousand,
			DividendSource source) {
	}

	public Long getId() {
		return id;
	}

	public Market getMarket() {
		return market;
	}

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public LocalDate getExDate() {
		return exDate;
	}

	public DividendKind getKind() {
		return kind;
	}

	public String getDetailStockNo() {
		return detailStockNo;
	}

	public LocalDate getDetailDate() {
		return detailDate;
	}

	public DividendDetailStatus getDetailStatus() {
		return detailStatus;
	}

	public BigDecimal getDetailCashPerShare() {
		return detailCashPerShare;
	}

	public BigDecimal getDetailStockPerThousand() {
		return detailStockPerThousand;
	}

	public LocalDateTime getDetailCheckedAt() {
		return detailCheckedAt;
	}

	public BigDecimal getCashPerShare() {
		return cashPerShare;
	}

	public BigDecimal getStockPerThousand() {
		return stockPerThousand;
	}

	public DividendSource getSource() {
		return source;
	}

	public LocalDateTime getListFetchedAt() {
		return listFetchedAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	@Override
	public String toString() {
		return market + " " + code + " " + exDate + " " + kind + " " + source + "（明細 " + detailStatus + "）";
	}

	/** 唯一鍵（市場、代號、除權息日），給 service 在記憶體裡比對同一筆用。 */
	public record Key(Market market, String code, LocalDate exDate) {
		public Key {
			Objects.requireNonNull(market);
			Objects.requireNonNull(code);
			Objects.requireNonNull(exDate);
		}

		public static Key of(DividendListRow row) {
			return new Key(row.market(), row.code(), row.exDate());
		}
	}

	public Key key() {
		return new Key(market, code, exDate);
	}
}
