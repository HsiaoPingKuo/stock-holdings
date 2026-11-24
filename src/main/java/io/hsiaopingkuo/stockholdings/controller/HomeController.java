package io.hsiaopingkuo.stockholdings.controller;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** 首頁：顯示標題與目前的台北時間。 */
@Controller
public class HomeController {

	private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final Clock clock;

	// 用建構子注入 Clock，測試時可以塞固定時間進來
	public HomeController(Clock clock) {
		this.clock = clock;
	}

	@GetMapping("/")
	public String home(Model model) {
		// 一定要傳 clock，不能用沒參數的 now()，否則會拿到主機時區（海外）的時間
		model.addAttribute("now", LocalDateTime.now(clock).format(FORMAT));
		// 給頁面上的 JS 當基準，讓時間以「伺服器時間」為準每秒往前走，不依賴使用者電腦的時鐘
		model.addAttribute("nowEpochMillis", clock.millis());
		return "index";
	}
}
