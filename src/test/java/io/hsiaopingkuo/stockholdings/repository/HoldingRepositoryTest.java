package io.hsiaopingkuo.stockholdings.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import io.hsiaopingkuo.stockholdings.entity.Holding;

/**
 * 驗證 V1 migration 建出來的表：欄位對得上 Entity，而且資料庫本身的約束有作用。
 * 這裡刻意繞過表單和 service，直接寫資料庫，確認「最後一道防線」真的存在。
 */
@DataJpaTest
class HoldingRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 14, 0);

	@Autowired
	private HoldingRepository holdingRepository;

	@Test
	void 可以存取持股且代號開頭的0不會掉() {
		Holding saved = holdingRepository.saveAndFlush(new Holding("0050", 2, new BigDecimal("150.00"), NOW));

		Holding found = holdingRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getCode()).isEqualTo("0050");
		assertThat(found.getLots()).isEqualTo(2);
		assertThat(found.getBuyPrice()).isEqualByComparingTo("150.00");
		assertThat(holdingRepository.existsByCode("0050")).isTrue();
	}

	@Test
	void 資料庫的UNIQUE約束擋下重複代號_錯誤訊息含約束名稱() {
		holdingRepository.saveAndFlush(new Holding("2330", 1, new BigDecimal("1000.00"), NOW));

		// HoldingService 靠錯誤訊息裡的約束名稱判斷是不是重複代號，這裡確認 H2 真的會帶出這個名稱
		assertThatThrownBy(() -> holdingRepository.saveAndFlush(new Holding("2330", 3, new BigDecimal("900.00"), NOW)))
				.isInstanceOf(DataIntegrityViolationException.class)
				.satisfies(e -> assertThat(((DataIntegrityViolationException) e).getMostSpecificCause().getMessage())
						.containsIgnoringCase("UK_HOLDING_CODE"));
	}

	@Test
	void 資料庫的CHECK約束擋下超出範圍的張數() {
		assertThatThrownBy(() -> holdingRepository.saveAndFlush(new Holding("2330", 1000, new BigDecimal("10.00"), NOW)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 資料庫的CHECK約束擋下不大於0的買進價() {
		assertThatThrownBy(() -> holdingRepository.saveAndFlush(new Holding("2330", 1, BigDecimal.ZERO, NOW)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 清單依代號排序() {
		holdingRepository.saveAndFlush(new Holding("6488", 1, new BigDecimal("460.50"), NOW));
		holdingRepository.saveAndFlush(new Holding("0050", 1, new BigDecimal("150.00"), NOW));
		holdingRepository.saveAndFlush(new Holding("2330", 1, new BigDecimal("1000.00"), NOW));

		assertThat(holdingRepository.findAllByOrderByCodeAsc())
				.extracting(Holding::getCode)
				.containsExactly("0050", "2330", "6488");
	}
}
