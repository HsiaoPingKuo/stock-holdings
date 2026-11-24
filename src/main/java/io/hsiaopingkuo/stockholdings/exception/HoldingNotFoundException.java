package io.hsiaopingkuo.stockholdings.exception;

/** 找不到指定 id 的持股（例如已在另一個分頁被刪掉）。 */
public class HoldingNotFoundException extends RuntimeException {

	public HoldingNotFoundException(Long id) {
		super("找不到這筆持股（id=" + id + "），可能已被刪除");
	}
}
