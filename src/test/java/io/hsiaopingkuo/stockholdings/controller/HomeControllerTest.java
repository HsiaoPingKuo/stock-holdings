package io.hsiaopingkuo.stockholdings.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HomeController.class)
class HomeControllerTest {

	// UTC 06:00 = 台北 14:00；頁面要顯示 14:00 才代表真的用台北時區
	private static final Instant FIXED = Instant.parse("2026-11-24T06:00:00Z");

	@TestConfiguration
	static class FixedClockConfig {
		@Bean
		Clock clock() {
			return Clock.fixed(FIXED, ZoneId.of("Asia/Taipei"));
		}
	}

	@Autowired
	private MockMvc mockMvc;

	@Test
	void 首頁顯示標題與台北時間() throws Exception {
		mockMvc.perform(get("/"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("<h1>持股記帳</h1>")))
				.andExpect(content().string(containsString("2026-11-24 14:00:00")));
	}

	@Test
	void 頁面帶有伺服器時間給JS每秒更新() throws Exception {
		mockMvc.perform(get("/"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("data-epoch-millis=\"" + FIXED.toEpochMilli() + "\"")))
				.andExpect(content().string(containsString("setInterval(render, 1000)")));
	}
}
