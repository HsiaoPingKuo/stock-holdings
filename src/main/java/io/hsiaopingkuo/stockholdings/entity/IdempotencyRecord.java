package io.hsiaopingkuo.stockholdings.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** 一個冪等鍵的處理紀錄（第 6 題決定 6-A），對應 V6__create_idempotency_record.sql。 */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "idempotency_key", nullable = false, length = 64)
	private String idempotencyKey;

	@Column(name = "api", nullable = false, length = 100)
	private String api;

	@Column(name = "request_hash", nullable = false, length = 64, columnDefinition = "char(64)")
	private String requestHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 11)
	private IdempotencyStatus status;

	@Column(name = "response_status")
	private Integer responseStatus;

	@Lob
	@Column(name = "response_body")
	private String responseBody;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	protected IdempotencyRecord() {
	}

	/** 開始處理：還沒有回應。時間由呼叫端用台北時區的 Clock 算好傳入。 */
	public static IdempotencyRecord inProgress(String key, String api, String requestHash, LocalDateTime now) {
		IdempotencyRecord record = new IdempotencyRecord();
		record.idempotencyKey = key;
		record.api = api;
		record.requestHash = requestHash;
		record.status = IdempotencyStatus.IN_PROGRESS;
		record.createdAt = now;
		return record;
	}

	/** 寫入成功：存下回給用戶端的狀態碼和內容，之後重送時原樣回傳。 */
	public void complete(int responseStatus, String responseBody) {
		this.status = IdempotencyStatus.COMPLETED;
		this.responseStatus = responseStatus;
		this.responseBody = responseBody;
	}

	public Long getId() {
		return id;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public String getApi() {
		return api;
	}

	public String getRequestHash() {
		return requestHash;
	}

	public IdempotencyStatus getStatus() {
		return status;
	}

	public Integer getResponseStatus() {
		return responseStatus;
	}

	public String getResponseBody() {
		return responseBody;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}
}
