package io.hsiaopingkuo.stockholdings.config;

import java.io.IOException;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.FlashMapManager;
import org.springframework.web.servlet.support.SessionFlashMapManager;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 網頁表單沒帶 CSRF token 或 token 錯誤時，導回清單頁，用現有的 errorMessage 顯示訊息。
 *
 * 最常見的原因不是攻擊，而是頁面開太久或伺服器重開，session 裡的 token 已經不在了，
 * 所以訊息請使用者重新整理；清單頁會重新產生 token，重送就能成功。
 */
public class CsrfDeniedHandler implements AccessDeniedHandler {

	public static final String MESSAGE = "表單已過期，請重新整理後再送出";

	// 這裡還在 Spring Security 的 filter，還沒進到 Spring MVC，不能用 RedirectAttributes；
	// 直接用 Spring MVC 同一套 session 機制存 flash 訊息，導回清單頁時一樣讀得到
	private final FlashMapManager flashMapManager = new SessionFlashMapManager();

	// 不是 CSRF 的拒絕（目前所有網址都 permitAll，理論上不會發生）交回 Spring Security 預設處理（403）
	private final AccessDeniedHandler fallback = new AccessDeniedHandlerImpl();

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException, ServletException {
		if (!(accessDeniedException instanceof CsrfException)) {
			fallback.handle(request, response, accessDeniedException);
			return;
		}
		String target = request.getContextPath() + "/holdings";
		FlashMap flashMap = new FlashMap();
		flashMap.put("errorMessage", MESSAGE);
		flashMap.setTargetRequestPath(target);
		flashMapManager.saveOutputFlashMap(flashMap, request, response);
		response.sendRedirect(target);
	}
}
