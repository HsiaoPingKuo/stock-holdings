package io.hsiaopingkuo.stockholdings.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.accept.ContentNegotiationStrategy;
import org.springframework.web.accept.HeaderContentNegotiationStrategy;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;

/**
 * App API（/api/）一律回 JSON，不看請求的 Accept 標頭（第 5 題）。
 *
 * 為什麼需要：Spring 會依 Accept（客戶端說「我要什麼格式」）決定回應格式，這叫內容協商（content negotiation）。
 * 客戶端只接受 JSON 以外的格式時（例如 Accept: text/html，沒有 *／*），Spring 找不到能回的格式，
 * 會回 406 和一個 HTML 或空白頁，不是規格的 {"error":{...}} 格式。
 * 規格寫「發生錯誤時，回應的格式一律是…」，而且 API 本來就只有 JSON 一種格式，所以 /api/ 直接當成「要 JSON」。
 *
 * 網頁（/api/ 以外）仍照原本的方式看 Accept 標頭，行為不變。
 */
@Configuration(proxyBeanMethods = false)
public class ApiContentNegotiationConfig implements WebMvcConfigurer {

	@Override
	public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
		// 依序詢問：第一個有明確答案的為準；回 MEDIA_TYPE_ALL_LIST 代表「我沒意見」，交給下一個
		configurer.strategies(List.of(new ApiAlwaysJson(), new HeaderContentNegotiationStrategy()));
	}

	/** /api/ 開頭的請求：不論 Accept 寫什麼，都當成要 application/json。 */
	static final class ApiAlwaysJson implements ContentNegotiationStrategy {

		@Override
		public List<MediaType> resolveMediaTypes(NativeWebRequest webRequest) {
			HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
			if (request != null && request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
				return List.of(MediaType.APPLICATION_JSON);
			}
			return MEDIA_TYPE_ALL_LIST;
		}
	}
}
