package io.hsiaopingkuo.stockholdings.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class QuoteParsingTest {

	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	@Test
	void 民國日期轉西元() {
		assertThat(QuoteParsing.parseRocDate("115/11/23")).isEqualTo(LocalDate.of(2026, 11, 23));
		assertThat(QuoteParsing.parseRocDate(" 115/12/01 ")).isEqualTo(LocalDate.of(2026, 12, 1));
	}

	@Test
	void 不是民國日期格式_丟例外() {
		assertThatThrownBy(() -> QuoteParsing.parseRocDate("20261123")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 價格去掉千分位() {
		assertThat(QuoteParsing.parsePrice("2,030.00")).isEqualByComparingTo("2030.00");
		assertThat(QuoteParsing.parsePrice("460.50")).isEqualByComparingTo("460.50");
	}

	@Test
	void 沒有成交的價格是null而不是0() {
		assertThat(QuoteParsing.parsePrice("--")).isNull();
		assertThat(QuoteParsing.parsePrice(" ")).isNull();
		assertThat(QuoteParsing.parsePrice(null)).isNull();
	}

	@Test
	void stat比對不分大小寫() {
		assertThat(QuoteParsing.isOk("OK")).isTrue();
		assertThat(QuoteParsing.isOk("ok")).isTrue();
		assertThat(QuoteParsing.isOk("很抱歉，沒有符合條件的資料!")).isFalse();
		assertThat(QuoteParsing.isOk(null)).isFalse();
	}

	@Test
	void 欄位名稱前後或中間有空白_仍然找得到() {
		JsonNode fields = jsonMapper.readTree("[\"日 期\",\"成交張數\",\"收盤 \"]");

		assertThat(QuoteParsing.indexOfField(fields, "日期")).isEqualTo(0);
		assertThat(QuoteParsing.indexOfField(fields, "收盤")).isEqualTo(2);
		assertThat(QuoteParsing.indexOfField(fields, "收盤價")).isEqualTo(-1);
	}

	@Test
	void 取最後一列的日期與收盤價() {
		JsonNode fields = jsonMapper.readTree("[\"日期\",\"收盤價\"]");
		JsonNode data = jsonMapper.readTree("[[\"115/11/20\",\"2,000.00\"],[\"115/11/23\",\"2,030.00\"]]");

		QuoteParsing.DailyClose last = QuoteParsing.lastDailyClose(fields, data, "收盤價");

		assertThat(last.date()).isEqualTo(LocalDate.of(2026, 11, 23));
		assertThat(last.closePrice()).isEqualByComparingTo("2030.00");
	}

	@Test
	void 找不到收盤欄位_丟例外() {
		JsonNode fields = jsonMapper.readTree("[\"日期\",\"開盤價\"]");
		JsonNode data = jsonMapper.readTree("[[\"115/11/23\",\"2,030.00\"]]");

		assertThatThrownBy(() -> QuoteParsing.lastDailyClose(fields, data, "收盤價"))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
