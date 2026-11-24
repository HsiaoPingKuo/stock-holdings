package io.hsiaopingkuo.stockholdings.dto;

/**
 * 冪等鍵存下來的回應，同一個鍵重送時原樣回傳（第 6 題決定 6-A）。
 * - /api/v2：status 是 201、200、204 等，body 是 controller 序列化好的 JSON 字串。
 *   存字串而不是物件：重送時回傳的內容和第一次逐字相同，不受之後程式改版影響。
 * - 2.0 網頁表單：status 是 303，body 放轉址的網址。
 *
 * @param body 沒有內容時（例如 204）是 null
 */
public record StoredResponse(int status, String body) {
}
