package io.hsiaopingkuo.stockholdings.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.hsiaopingkuo.stockholdings.calc.DividendKind;
import io.hsiaopingkuo.stockholdings.quote.DividendParsing.DetailParam;

/** 除權息 JSON 的格式邊界：民國日期、明細的單位、種類文字、明細參數。 */
class DividendParsingTest {

	// ===== 民國日期 =====

	@Test
	void 中文民國日期_114年06月12日是2025年6月12日() {
		assertThat(DividendParsing.parseRocChineseDate("114年06月12日")).isEqualTo(LocalDate.of(2025, 6, 12));
	}

	@Test
	void 中文民國日期_最早範圍110年01月05日() {
		assertThat(DividendParsing.parseRocChineseDate("110年01月05日")).isEqualTo(LocalDate.of(2021, 1, 5));
	}

	@Test
	void 中文民國日期_月日一位數_前後空白_兩位數年份都接受() {
		assertThat(DividendParsing.parseRocChineseDate(" 114年6月2日 ")).isEqualTo(LocalDate.of(2025, 6, 2));
		assertThat(DividendParsing.parseRocChineseDate("99年12月31日")).isEqualTo(LocalDate.of(2010, 12, 31));
	}

	@ParameterizedTest
	@ValueSource(strings = { "114年13月01日", "114年02月30日", "2025-06-12", "114/06/12", "", "114年06月" })
	void 中文民國日期_格式不對或日期不存在_丟例外(String value) {
		assertThatThrownBy(() -> DividendParsing.parseRocChineseDate(value))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 中文民國日期_null丟例外() {
		assertThatThrownBy(() -> DividendParsing.parseRocChineseDate(null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 斜線民國日期_沿用行情的解析_114斜線07斜線24是2025年7月24日() {
		assertThat(QuoteParsing.parseRocDate("114/07/24")).isEqualTo(LocalDate.of(2025, 7, 24));
	}

	// ===== 明細的數字和單位 =====

	@Test
	void 明細數字_全形斜線的元每股() {
		assertThat(DividendParsing.parseDetailAmount("1.2 元／股")).isEqualByComparingTo("1.2");
	}

	@Test
	void 明細數字_保留原本的小數位數() {
		BigDecimal value = DividendParsing.parseDetailAmount("4.50002 元／股");

		assertThat(value).isEqualTo(new BigDecimal("4.50002"));
		assertThat(value.scale()).isEqualTo(5);
	}

	@Test
	void 明細數字_股_千分位_八位小數() {
		assertThat(DividendParsing.parseDetailAmount("10 股")).isEqualByComparingTo("10");
		assertThat(DividendParsing.parseDetailAmount("18,000,000 股")).isEqualByComparingTo("18000000");
		assertThat(DividendParsing.parseDetailAmount("0.00000000 股")).isEqualByComparingTo("0");
		assertThat(DividendParsing.parseDetailAmount("0 股")).isEqualByComparingTo("0");
	}

	@Test
	void 明細數字_空字串和空白當成0() {
		assertThat(DividendParsing.parseDetailAmount("")).isEqualByComparingTo("0");
		assertThat(DividendParsing.parseDetailAmount("   ")).isEqualByComparingTo("0");
	}

	@Test
	void 明細數字_半形斜線和沒有單位也接受() {
		assertThat(DividendParsing.parseDetailAmount("1.2 元/股")).isEqualByComparingTo("1.2");
		assertThat(DividendParsing.parseDetailAmount("1.2")).isEqualByComparingTo("1.2");
	}

	@ParameterizedTest
	@ValueSource(strings = { "abc", "-1 股", "--", "1.2 元", "1.2 元／股 10 股", "股" })
	void 明細數字_看不懂或負數_丟例外(String value) {
		assertThatThrownBy(() -> DividendParsing.parseDetailAmount(value))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 明細數字_null丟例外() {
		assertThatThrownBy(() -> DividendParsing.parseDetailAmount(null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// ===== 列表的價格 =====

	@Test
	void 列表價格_去千分位_看不懂或減號回null() {
		assertThat(DividendParsing.parseNumberOrNull("1,065.00")).isEqualByComparingTo("1065.00");
		assertThat(DividendParsing.parseNumberOrNull("4.500020")).isEqualTo(new BigDecimal("4.500020"));
		assertThat(DividendParsing.parseNumberOrNull("--")).isNull();
		assertThat(DividendParsing.parseNumberOrNull("")).isNull();
		assertThat(DividendParsing.parseNumberOrNull("N/A")).isNull();
		assertThat(DividendParsing.parseNumberOrNull(null)).isNull();
	}

	// ===== 種類 =====

	@Test
	void 種類_上市和上櫃的六種寫法() {
		assertThat(DividendParsing.parseKind("息")).isEqualTo(DividendKind.CASH);
		assertThat(DividendParsing.parseKind("權")).isEqualTo(DividendKind.STOCK);
		assertThat(DividendParsing.parseKind("權息")).isEqualTo(DividendKind.CASH_AND_STOCK);
		assertThat(DividendParsing.parseKind("除息")).isEqualTo(DividendKind.CASH);
		assertThat(DividendParsing.parseKind("除權")).isEqualTo(DividendKind.STOCK);
		assertThat(DividendParsing.parseKind(" 除權息 ")).isEqualTo(DividendKind.CASH_AND_STOCK);
	}

	@ParameterizedTest
	@ValueSource(strings = { "減資", "", "息權" })
	void 種類_不認得丟例外(String value) {
		assertThatThrownBy(() -> DividendParsing.parseKind(value)).isInstanceOf(IllegalArgumentException.class);
	}

	// ===== 明細參數 =====

	@Test
	void 明細參數_代號和西元日期() {
		assertThat(DividendParsing.parseDetailParam("2884,20250722"))
				.isEqualTo(new DetailParam("2884", LocalDate.of(2025, 7, 22)));
		assertThat(DividendParsing.parseDetailParam("00953B,20250604"))
				.isEqualTo(new DetailParam("00953B", LocalDate.of(2025, 6, 4)));
	}

	@Test
	void 明細參數_空白或格式不對回null() {
		assertThat(DividendParsing.parseDetailParam("")).isNull();
		assertThat(DividendParsing.parseDetailParam(null)).isNull();
		assertThat(DividendParsing.parseDetailParam("2884")).isNull();
		assertThat(DividendParsing.parseDetailParam("2884,20251332")).isNull();
	}
}
