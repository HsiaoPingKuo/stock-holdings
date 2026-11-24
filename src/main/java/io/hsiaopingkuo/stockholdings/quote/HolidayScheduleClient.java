package io.hsiaopingkuo.stockholdings.quote;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

/**
 * 取得證交所公布的休市日（上市、上櫃的休市日相同，共用一份）。
 * 包成 interface，測試時才能換成假的，不必連網。
 */
public interface HolidayScheduleClient {

	/**
	 * @return 休市日（只含真的不開市的日子）；查不到、格式不符、連不上時回 empty，呼叫端當成「每天都是交易日」
	 */
	Optional<Set<LocalDate>> fetchClosedDates();
}
