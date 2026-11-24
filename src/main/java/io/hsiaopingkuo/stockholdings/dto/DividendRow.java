package io.hsiaopingkuo.stockholdings.dto;

import io.hsiaopingkuo.stockholdings.calc.DividendDetail;

/**
 * 網頁明細頁的一次除權息（第 6 題）。現金股利用和 /api/v2 相同的整數元。
 */
public record DividendRow(DividendDetail detail) {

	// 二代健保：單次給付 2 萬元以上，入帳時先扣 2.11% 補充保費
	static final long SUPPLEMENTARY_PREMIUM_THRESHOLD = 20_000L;

	/** 現金股利（整數元）；「無法取得」時是 null。 */
	public Long cash() {
		return ApiHoldingResponse.toWholeYuan(detail.cashDividend());
	}

	public boolean supplementaryPremium() {
		Long cash = cash();
		return cash != null && cash >= SUPPLEMENTARY_PREMIUM_THRESHOLD;
	}

	public String kindLabel() {
		return switch (detail.kind()) {
			case CASH -> "息";
			case STOCK -> "權";
			case CASH_AND_STOCK -> "權息";
		};
	}

	public String sourceLabel() {
		return switch (detail.source()) {
			case OFFICIAL -> "官方";
			case ESTIMATED -> "估算";
			case UNAVAILABLE -> "無法取得";
		};
	}
}
