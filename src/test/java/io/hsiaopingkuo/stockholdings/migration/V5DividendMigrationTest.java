package io.hsiaopingkuo.stockholdings.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 第 6 題 V5 migration：除權息事件和已抓範圍兩張表。
 * 先跑到 V4、寫入 1.0 轉入的資料，再跑 V5，確認升級不影響既有資料，並檢查約束。
 * 不啟動 Spring：每個測試自己開一個全新的記憶體資料庫，才能控制 migration 跑到哪一版。
 */
class V5DividendMigrationTest {

	private static final Timestamp NOW = Timestamp.valueOf(LocalDateTime.of(2026, 11, 25, 15, 0));

	private DriverManagerDataSource dataSource;
	private JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		// 名稱用 UUID：每個測試是獨立的資料庫，不會和其他測試（含 Spring 的 testdb）互相影響
		dataSource = new DriverManagerDataSource(
				"jdbc:h2:mem:v5-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
		jdbc = new JdbcTemplate(dataSource);
		migrate("4");
	}

	private void migrate(String target) {
		Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.target(target)
				.load()
				.migrate();
	}

	/** 寫一筆除權息事件（只填必填欄位和採用的值），用來測約束。 */
	private void insertEvent(String market, String code, String exDate, String source, BigDecimal cash,
			BigDecimal stock) {
		jdbc.update("INSERT INTO dividend_event (market, code, ex_date, kind, detail_status, cash_per_share, "
				+ "stock_per_thousand, source, list_fetched_at, created_at, updated_at) "
				+ "VALUES (?, ?, ?, 'CASH', 'NOT_NEEDED', ?, ?, ?, ?, ?, ?)",
				market, code, Date.valueOf(exDate), cash, stock, source, NOW, NOW, NOW);
	}

	private void insertRange(String market, int year) {
		jdbc.update("INSERT INTO dividend_fetch_range (market, fetch_year, completed, last_fetched_at) "
				+ "VALUES (?, ?, TRUE, ?)", market, year, NOW);
	}

	@Test
	void 從V4升級到V5_兩張表建好_既有的持股和交易不受影響() {
		jdbc.update("INSERT INTO holding (code, lots, buy_price, market, created_at, updated_at) "
				+ "VALUES ('2330', 1, 1000.00, 'TWSE', ?, ?)", NOW, NOW);
		long holdingId = jdbc.queryForObject("SELECT id FROM holding WHERE code = '2330'", Long.class);
		jdbc.update("INSERT INTO trade (holding_id, side, trade_date, shares, price, date_estimated, version, "
				+ "created_at, updated_at) VALUES (?, 'BUY', DATE '2026-11-25', 1000, 1000.00, TRUE, 0, ?, ?)",
				holdingId, NOW, NOW);

		migrate("latest");

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM holding", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dividend_event", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dividend_fetch_range", Integer.class)).isZero();
	}

	@Test
	void 除權息事件_同市場同代號同日期只能一筆_不同市場可以() {
		migrate("latest");
		insertEvent("TWSE", "2330", "2025-06-12", "OFFICIAL", new BigDecimal("4.5"), BigDecimal.ZERO);

		assertThatThrownBy(() -> insertEvent("TWSE", "2330", "2025-06-12", "OFFICIAL", new BigDecimal("4.5"),
				BigDecimal.ZERO))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("UK_DIVIDEND_EVENT_MARKET_CODE_DATE");
		// 轉上市的股票：上櫃時期的事件和上市的事件分開存
		insertEvent("TPEX", "2330", "2025-06-12", "OFFICIAL", new BigDecimal("4.5"), BigDecimal.ZERO);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dividend_event", Integer.class)).isEqualTo(2);
	}

	@Test
	void 已抓範圍_同市場同年份只能一筆() {
		migrate("latest");
		insertRange("TWSE", 2025);
		insertRange("TPEX", 2025);

		assertThatThrownBy(() -> insertRange("TWSE", 2025))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("UK_DIVIDEND_FETCH_RANGE_MARKET_YEAR");
	}

	@Test
	void 八位小數的配股率_存進去讀出來完全一樣() {
		migrate("latest");
		insertEvent("TPEX", "6123", "2025-07-01", "OFFICIAL", BigDecimal.ZERO, new BigDecimal("99.99999623"));

		assertThat(jdbc.queryForObject("SELECT stock_per_thousand FROM dividend_event", BigDecimal.class))
				.isEqualByComparingTo("99.99999623");
	}

	@Test
	void 採用的值_官方或估算一定要有數字_無法取得一定是空的() {
		migrate("latest");

		assertThatThrownBy(() -> insertEvent("TWSE", "2884", "2025-07-22", "OFFICIAL", null, BigDecimal.ZERO))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("CK_DIVIDEND_EVENT_AMOUNTS");
		assertThatThrownBy(() -> insertEvent("TWSE", "2884", "2025-07-22", "UNAVAILABLE", BigDecimal.ONE, null))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("CK_DIVIDEND_EVENT_AMOUNTS");
		assertThatThrownBy(() -> insertEvent("TWSE", "2884", "2025-07-22", "ESTIMATED", new BigDecimal("-1"),
				BigDecimal.ZERO))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("CK_DIVIDEND_EVENT_AMOUNTS");

		insertEvent("TWSE", "2884", "2025-07-22", "UNAVAILABLE", null, null);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dividend_event", Integer.class)).isEqualTo(1);
	}
}
