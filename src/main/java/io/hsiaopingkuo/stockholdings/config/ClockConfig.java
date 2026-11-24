package io.hsiaopingkuo.stockholdings.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 正式機在海外、主機時區不是台北，但使用者都在台灣。
 * 所以全系統的「現在時間」都從這個固定 Asia/Taipei 的 Clock 取得，
 * 測試時也能換成固定時間的 Clock。
 */
@Configuration
public class ClockConfig {

	public static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

	@Bean
	public Clock clock() {
		return Clock.system(TAIPEI);
	}
}
