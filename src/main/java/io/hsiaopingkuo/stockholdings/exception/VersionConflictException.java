package io.hsiaopingkuo.stockholdings.exception;

/**
 * 修改、刪除交易時帶來的版本號和資料庫的不一樣（第 6 題決定 6-B，樂觀鎖）：
 * 使用者看到的是舊資料，別的裝置已經改過。直接蓋掉會讓對方的修改消失，所以拒絕，請使用者重新整理。
 * API 回 409 VERSION_CONFLICT。
 */
public class VersionConflictException extends RuntimeException {

	public VersionConflictException() {
		super("這筆交易已被其他裝置修改，請重新整理");
	}
}
