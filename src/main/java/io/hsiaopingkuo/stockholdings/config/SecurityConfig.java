package io.hsiaopingkuo.stockholdings.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.RequestRejectedHandler;

import io.hsiaopingkuo.stockholdings.api.ApiRequestRejectedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Security 只用來做 CSRF 防護（防止別的網站偷偷用使用者的瀏覽器送出表單，改掉持股），不做登入。
 *
 * - 網頁表單（POST）要帶 CSRF token：Thymeleaf 用 th:action 的表單會自動加上隱藏欄位 _csrf。
 * - /api/** 不檢查：App 送 JSON、不靠 cookie 登入，別的網站沒辦法冒用 App 的身分，CSRF 打不到。
 * - 所有網址都不用登入；預設的登入頁、HTTP Basic、登出網址都關掉，避免出現用不到的畫面。
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
				.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				// 沒帶 token 或 token 錯誤時，導回清單頁顯示「表單已過期」，不出現預設的 403 錯誤頁
				.exceptionHandling(handling -> handling.accessDeniedHandler(new CsrfDeniedHandler()));
		return http.build();
	}

	// Spring Security 會自動找這個型別的 bean：網址被它的檢查擋下時（例如含 // 或 ;），
	// /api 仍回 {"error":{...}} 格式，網頁維持預設的 400
	@Bean
	RequestRejectedHandler requestRejectedHandler(JsonMapper jsonMapper) {
		return new ApiRequestRejectedHandler(jsonMapper);
	}
}
