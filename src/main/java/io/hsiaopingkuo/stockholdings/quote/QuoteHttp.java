package io.hsiaopingkuo.stockholdings.quote;

import java.net.URI;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 所有官方 client 共用的「送出 GET、拿回 JSON」。
 * 不丟例外，把結果分成三種：
 * - OK：拿到 JSON
 * - BLOCKED：被官方擋下（HTTP 403、429，或回傳 HTML 頁面）→ 排程要冷卻一段時間，不能馬上再打
 * - FAILED：其他拿不到 JSON 的情況（逾時、連不上、404、500、JSON 壞掉）→ 可以改用別的 API 試試
 */
final class QuoteHttp {

	private static final Logger log = LoggerFactory.getLogger(QuoteHttp.class);

	/** 403：拒絕存取；429：請求太頻繁。兩個都代表「被擋」。 */
	private static final int FORBIDDEN = 403;
	private static final int TOO_MANY_REQUESTS = 429;

	private QuoteHttp() {
	}

	enum Outcome {
		OK, BLOCKED, FAILED
	}

	/** @param json 只有 OK 時才有值 */
	record Response(Outcome outcome, JsonNode json) {

		static Response blocked() {
			return new Response(Outcome.BLOCKED, null);
		}

		static Response failed() {
			return new Response(Outcome.FAILED, null);
		}
	}

	static Response getJson(RestClient restClient, JsonMapper jsonMapper, Function<UriBuilder, URI> uriFunction,
			String description) {
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
				return Response.failed();
			}
			MediaType contentType = response.getHeaders().getContentType();
			if ((contentType != null && contentType.isCompatibleWith(MediaType.TEXT_HTML)) || startsWithTag(body)) {
				// 官方擋爬蟲時常回 HTML 頁面，不是 JSON；當成被擋，避免繼續打而被擋更久
				log.warn("{}：回傳的是 HTML，可能被擋或官方維護中", description);
				return Response.blocked();
			}
			return new Response(Outcome.OK, jsonMapper.readTree(body));
		} catch (RestClientResponseException e) {
			// 4xx/5xx：先挑出「被擋」的狀態碼，其餘（404、500…）算一般失敗
			int status = e.getStatusCode().value();
			if (status == FORBIDDEN || status == TOO_MANY_REQUESTS) {
				log.warn("{}：被官方擋下（HTTP {}）", description, status);
				return Response.blocked();
			}
			log.warn("{}：HTTP 錯誤 {}", description, status);
			return Response.failed();
		} catch (RestClientException e) {
			// 逾時、連不上（ResourceAccessException）
			log.warn("{}：連線失敗：{}", description, e.toString());
			return Response.failed();
		} catch (JacksonException e) {
			log.warn("{}：JSON 解析失敗：{}", description, e.getOriginalMessage());
			return Response.failed();
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
