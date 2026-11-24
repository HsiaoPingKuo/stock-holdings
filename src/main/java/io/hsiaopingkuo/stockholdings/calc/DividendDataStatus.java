package io.hsiaopingkuo.stockholdings.calc;

/**
 * 一檔持股的股利資料狀態（第 6 題設計決定 7-D），第 6 階段的網頁和 /api/v2 的 dividend_status。
 * 股利資料每天更新一次：今年只要抓過一次就算數，當天稍晚才公布的除權息要等下一輪。
 */
public enum DividendDataStatus {
	/** 持有期間的除權息資料都抓過了。 */
	COMPLETE,
	/** 還有年份沒抓（或沒抓完），或有明細還在待查：網頁顯示「股利資料更新中，稍後重新整理」。 */
	PENDING
}
