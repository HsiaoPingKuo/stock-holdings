package io.hsiaopingkuo.stockholdings.quote;

import java.net.URI;
import java.util.Optional;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 兩支逐檔 client 共用的「送出 GET、拿回 JSON」。
 * 所有「拿不到可用 JSON」的情況（逾時、HTTP 錯誤、被擋回 HTML、JSON 壞掉）都回 Optional.empty()，
 * 呼叫端一律當成 UNAVAILABLE，不會讓例外一路丟到畫面上變成 500。
 */
final class QuoteHttp {

	private static final Logger log = LoggerFactory.getLogger(QuoteHttp.class);

	private QuoteHttp() {
	}

	static Optional<JsonNode> getJson(RestClient restClient, JsonMapper jsonMapper,
			Function<UriBuilder, URI> uriFunction, String description) {
		try {
			// 先拿原始 bytes 再交給 Jackson：官方回應如果沒寫 charset，讀成 String 時中文可能變亂碼；
			// Jackson 讀 bytes 會自己判斷 UTF-8
			ResponseEntity<byte[]> response = restClient.get()
					.uri(uriFunction)
					.accept(MediaType.APPLICATION_JSON)
					.retrieve()
					.toEntity(byte[].class);
			byte[] body = response.getBody();
			if (body == null || body.length == 0) {
				log.warn("{}：回應是空的", description);
				return Optional.empty();
			}
			MediaType contentType = response.getHeaders().getContentType();
			if ((contentType != null && contentType.isCompatibleWith(MediaType.TEXT_HTML)) || startsWithTag(body)) {
				// 官方擋爬蟲或維護時常回 HTML 頁面，不是 JSON
				log.warn("{}：回傳的是 HTML，可能被擋或官方維護中", description);
				return Optional.empty();
			}
			return Optional.of(jsonMapper.readTree(body));
		} catch (RestClientException e) {
			// 涵蓋逾時、連不上（ResourceAccessException）和 4xx/5xx（RestClientResponseException）
			log.warn("{}：連線失敗或 HTTP 錯誤：{}", description, e.toString());
			return Optional.empty();
		} catch (JacksonException e) {
			log.warn("{}：JSON 解析失敗：{}", description, e.getOriginalMessage());
			return Optional.empty();
		}
	}

	/** 跳過開頭空白後第一個字是 '<'，就當成 HTML。 */
	private static boolean startsWithTag(byte[] body) {
		for (byte b : body) {
			if (!Character.isWhitespace(b)) {
				return b == '<';
			}
		}
		return false;
	}
}
