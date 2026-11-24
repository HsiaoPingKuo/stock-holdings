package io.hsiaopingkuo.stockholdings.api;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;

import io.hsiaopingkuo.stockholdings.dto.ErrorResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Security 會先檢查網址，含 //、;、編碼過的斜線等可疑字元時直接擋下（還沒進到 controller），
 * 預設只回 400、沒有內容，App 拿到的就不是 {"error":{...}} 格式。
 * 這裡讓 /api 開頭的請求也回規格的錯誤格式；其他網址照 Spring Security 預設回 400。
 */
public class ApiRequestRejectedHandler implements RequestRejectedHandler {

	private final RequestRejectedHandler fallback = new HttpStatusRequestRejectedHandler();
	private final JsonMapper jsonMapper;

	public ApiRequestRejectedHandler(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			RequestRejectedException requestRejectedException) throws IOException, ServletException {
		if (!request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
			fallback.handle(request, response, requestRejectedException);
			return;
		}
		response.setStatus(HttpStatus.BAD_REQUEST.value());
		response.setContentType(ApiJson.JSON_UTF8_VALUE);
		// 不把被擋的網址放進訊息：網址本身就是可疑內容，不需要原樣回給客戶端
		jsonMapper.writeValue(response.getOutputStream(),
				ErrorResponse.of("BAD_REQUEST", "網址格式不正確，不可包含 //、; 或編碼過的斜線等字元"));
	}
}
