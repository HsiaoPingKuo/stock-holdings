package io.hsiaopingkuo.stockholdings.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 第 6 題 V4 migration：模擬「用 1.0 的資料庫升級」。
 * 先只跑到 V3（等於 1.0 的資料表），寫入 1.0 的持股，再跑 V4，檢查轉入的交易和放寬後的限制。
 * 不啟動 Spring：每個測試自己開一個全新的記憶體資料庫，才能控制 migration 跑到哪一版。
 */
class V4TradeMigrationTest {

	private DriverManagerDataSource dataSource;
	private JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		// 名稱用 UUID：每個測試是獨立的資料庫，不會和其他測試（含 Spring 的 testdb）互相影響
		dataSource = new DriverManagerDataSource(
				"jdbc:h2:mem:v4-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
		jdbc = new JdbcTemplate(dataSource);
		migrate("3");
	}

	private void migrate(String target) {
		Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.target(target)
				.load()
				.migrate();
	}

	private void migrateToLatest() {
		migrate("latest");
	}

	/** 照 1.0 的寫法新增一筆持股（market、name 是 V2 才加的，可以是 NULL），回傳 id。 */
	private long insertHolding(String code, int lots, String buyPrice, LocalDateTime createdAt,
			LocalDateTime updatedAt) {
		jdbc.update("INSERT INTO holding (code, lots, buy_price, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
				code, lots, new BigDecimal(buyPrice), Timestamp.valueOf(createdAt), Timestamp.valueOf(updatedAt));
		return jdbc.queryForObject("SELECT id FROM holding WHERE code = ?", Long.class, code);
	}

	private Map<String, Object> tradeOf(long holdingId) {
		return jdbc.queryForMap("SELECT * FROM trade WHERE holding_id = ?", holdingId);
	}

	private static LocalDate dateOf(Object value) {
		return ((Date) value).toLocalDate();
	}

	private static LocalDateTime timeOf(Object value) {
		return ((Timestamp) value).toLocalDateTime();
	}

	/** 直接寫一筆交易，用來測資料庫的約束。 */
	private void insertTrade(long holdingId, String side, String tradeDate, long shares, String price) {
		jdbc.update("INSERT INTO trade (holding_id, side, trade_date, shares, price, date_estimated, version, "
				+ "created_at, updated_at) VALUES (?, ?, ?, ?, ?, FALSE, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
				holdingId, side, Date.valueOf(tradeDate), shares, new BigDecimal(price));
	}

	@Test
	void 每筆持股剛好轉成一筆推估的買進() {
		LocalDateTime created = LocalDateTime.of(2026, 11, 20, 10, 30);
		LocalDateTime updated = LocalDateTime.of(2026, 11, 22, 9, 15);
		long tsmc = insertHolding("2330", 1, "1000.00", created, updated);
		long max = insertHolding("6488", 999, "460.50", created, created);
		long etf = insertHolding("0050", 3, "150.25", created, created);
		// 晚上 23:59:59 建立：日期仍是當天，不會因為時區或捨入跑到隔天
		long lateNight = insertHolding("2884", 2, "29.80", LocalDateTime.of(2026, 11, 23, 23, 59, 59),
				LocalDateTime.of(2026, 11, 23, 23, 59, 59));

		migrateToLatest();

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade", Long.class)).isEqualTo(4);
		// 每檔持股剛好一筆
		assertThat(jdbc.queryForList("SELECT holding_id FROM trade ORDER BY holding_id", Long.class))
				.containsExactly(tsmc, max, etf, lateNight);

		Map<String, Object> t = tradeOf(tsmc);
		assertThat(t.get("SIDE")).isEqualTo("BUY");
		assertThat(t.get("SHARES")).isEqualTo(1000L);
		assertThat((BigDecimal) t.get("PRICE")).isEqualByComparingTo("1000.00");
		assertThat(dateOf(t.get("TRADE_DATE"))).isEqualTo(LocalDate.of(2026, 11, 20));
		assertThat(t.get("DATE_ESTIMATED")).isEqualTo(true);
		assertThat(t.get("VERSION")).isEqualTo(0L);
		// 時間沿用持股原本的值，不用主機時區的 CURRENT_TIMESTAMP
		assertThat(timeOf(t.get("CREATED_AT"))).isEqualTo(created);
		assertThat(timeOf(t.get("UPDATED_AT"))).isEqualTo(updated);

		// 999 張 → 999,000 股
		assertThat(tradeOf(max).get("SHARES")).isEqualTo(999_000L);
		assertThat((BigDecimal) tradeOf(max).get("PRICE")).isEqualByComparingTo("460.50");

		// 00 開頭的 ETF：代號的 0 沒掉，對應到正確的持股
		assertThat(jdbc.queryForObject("SELECT h.code FROM trade t JOIN holding h ON h.id = t.holding_id "
				+ "WHERE t.holding_id = ?", String.class, etf)).isEqualTo("0050");
		assertThat(tradeOf(etf).get("SHARES")).isEqualTo(3000L);
		assertThat((BigDecimal) tradeOf(etf).get("PRICE")).isEqualByComparingTo("150.25");

		assertThat(dateOf(tradeOf(lateNight).get("TRADE_DATE"))).isEqualTo(LocalDate.of(2026, 11, 23));
		assertThat(tradeOf(lateNight).get("DATE_ESTIMATED")).isEqualTo(true);
	}

	@Test
	void 建立日期早於2021年時日期拉到2021年1月1日並標示推估() {
		long old = insertHolding("2330", 1, "500.00", LocalDateTime.of(2020, 12, 31, 12, 0),
				LocalDateTime.of(2020, 12, 31, 12, 0));

		// 沒有 GREATEST 的話，ck_trade_date 會讓 V4 失敗、伺服器啟動不了
		migrateToLatest();

		assertThat(dateOf(tradeOf(old).get("TRADE_DATE"))).isEqualTo(LocalDate.of(2021, 1, 1));
		assertThat(tradeOf(old).get("DATE_ESTIMATED")).isEqualTo(true);
	}

	@Test
	void 沒有持股時V4也能跑完() {
		migrateToLatest();

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade", Long.class)).isZero();
	}

	@Test
	void 放寬後可以寫入張數和買進價是NULL的持股() {
		migrateToLatest();

		// 證明 ALTER COLUMN ... DROP NOT NULL 在 H2 真的生效
		jdbc.update("INSERT INTO holding (code, lots, buy_price, created_at, updated_at) "
				+ "VALUES ('2330', NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");

		Map<String, Object> row = jdbc.queryForMap("SELECT lots, buy_price FROM holding WHERE code = '2330'");
		assertThat(row.get("LOTS")).isNull();
		assertThat(row.get("BUY_PRICE")).isNull();
	}

	@Test
	void 放寬後1點0的張數範圍和買進價限制已拿掉() {
		migrateToLatest();

		// V1 的 ck_holding_lots（1～999）、ck_holding_buy_price（> 0）都已刪除
		jdbc.update("INSERT INTO holding (code, lots, buy_price, created_at, updated_at) "
				+ "VALUES ('2330', 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
		jdbc.update("INSERT INTO holding (code, lots, buy_price, created_at, updated_at) "
				+ "VALUES ('2317', 1000, 100, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM holding", Long.class)).isEqualTo(2);
	}

	@Test
	void trade的檢查約束擋下不合理的資料() {
		long id = insertHolding("2330", 1, "1000.00", LocalDateTime.of(2026, 11, 20, 10, 0),
				LocalDateTime.of(2026, 11, 20, 10, 0));
		migrateToLatest();

		assertThatThrownBy(() -> insertTrade(id, "BUY", "2026-11-20", 0, "1000.00"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("CK_TRADE_SHARES");
		assertThatThrownBy(() -> insertTrade(id, "BUY", "2026-11-20", 1000, "0.00"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("CK_TRADE_PRICE");
		assertThatThrownBy(() -> insertTrade(id, "BUY", "2020-12-31", 1000, "1000.00"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("CK_TRADE_DATE");
		assertThatThrownBy(() -> insertTrade(id, "HOLD", "2026-11-20", 1000, "1000.00"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("CK_TRADE_SIDE");

		// 只有轉入的那一筆，上面四筆都沒寫進去
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade", Long.class)).isEqualTo(1);
	}

	@Test
	void 最早日期2021年1月1日可以寫入() {
		long id = insertHolding("2330", 1, "1000.00", LocalDateTime.of(2026, 11, 20, 10, 0),
				LocalDateTime.of(2026, 11, 20, 10, 0));
		migrateToLatest();

		insertTrade(id, "SELL", "2021-01-01", 1, "0.01");

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade WHERE trade_date = DATE '2021-01-01'",
				Long.class)).isEqualTo(1);
	}

	@Test
	void 刪除持股時連帶刪除它的交易() {
		LocalDateTime created = LocalDateTime.of(2026, 11, 20, 10, 0);
		long tsmc = insertHolding("2330", 1, "1000.00", created, created);
		long other = insertHolding("2317", 2, "200.00", created, created);
		migrateToLatest();
		insertTrade(tsmc, "SELL", "2026-11-24", 500, "1100.00");

		// 和現有的刪除功能一樣只刪 holding；ON DELETE CASCADE 讓外鍵不會擋下
		jdbc.update("DELETE FROM holding WHERE id = ?", tsmc);

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade WHERE holding_id = ?", Long.class, tsmc))
				.isZero();
		// 其他持股的交易不受影響
		List<Long> remaining = jdbc.queryForList("SELECT holding_id FROM trade", Long.class);
		assertThat(remaining).containsExactly(other);
	}

	@Test
	void 外鍵擋下不存在的持股id() {
		migrateToLatest();

		assertThatThrownBy(() -> insertTrade(999L, "BUY", "2026-11-20", 1000, "1000.00"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("FK_TRADE_HOLDING");
	}
}
