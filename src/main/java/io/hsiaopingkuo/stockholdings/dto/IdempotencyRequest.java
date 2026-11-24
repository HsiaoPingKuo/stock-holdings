package io.hsiaopingkuo.stockholdings.dto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 判斷「是不是同一個請求」的三樣東西（第 6 題決定 6-A）：鍵、API、請求內容的雜湊值。
 *
 * @param key         用戶端帶的 Idempotency-Key（或網頁表單的一次性編號）
 * @param api         方法＋路徑樣板，例如 "PUT /api/v2/trades/{id}"
 * @param requestHash 請求內容的 SHA-256，用 {@link #sha256(String)} 算；
 *                    原文要包含實際路徑（例如交易 id），同一個鍵改送另一筆交易才會被當成內容不同
 */
public record IdempotencyRequest(String key, String api, String requestHash) {

	/** 算出 64 個十六進位字元的 SHA-256；原文先轉成 UTF-8，中文在任何主機上算出來都一樣。 */
	public static String sha256(String content) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			// 每個 Java 執行環境都規定要有 SHA-256，不會發生
			throw new IllegalStateException(e);
		}
	}
}
