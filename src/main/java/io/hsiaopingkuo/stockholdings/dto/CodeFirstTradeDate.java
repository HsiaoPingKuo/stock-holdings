package io.hsiaopingkuo.stockholdings.dto;

import java.time.LocalDate;

/**
 * 一檔持股的代號和第一筆交易日（第 6 題）：除權息同步用來決定哪些事件需要查明細
 * （除權息日晚於第一筆交易日的才會影響這檔）。由 TradeRepository 的查詢直接產生。
 */
public record CodeFirstTradeDate(String code, LocalDate firstTradeDate) {
}
