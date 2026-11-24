package io.hsiaopingkuo.stockholdings.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.hsiaopingkuo.stockholdings.config.IdempotencyProperties;
import io.hsiaopingkuo.stockholdings.dto.IdempotencyRequest;
import io.hsiaopingkuo.stockholdings.dto.StoredResponse;
import io.hsiaopingkuo.stockholdings.entity.IdempotencyRecord;
import io.hsiaopingkuo.stockholdings.entity.IdempotencyStatus;
import io.hsiaopingkuo.stockholdings.entity.Trade;
import io.hsiaopingkuo.stockholdings.exception.IdempotencyKeyReusedException;
import io.hsiaopingkuo.stockholdings.exception.RequestInProgressException;
import io.hsiaopingkuo.stockholdings.repository.IdempotencyRecordRepository;

/**
 * 冪等鍵（第 6 題決定 6-A）：同一個鍵只處理一次，重送時回傳上次的結果。
 *
 * 冪等鍵紀錄和交易寫在同一個資料庫交易裡（透過 TradeWriteHook 借用 TradeService 的交易）：
 * - 成功：鍵和回應跟著交易一起 commit
 * - 失敗（驗證錯誤、超賣、版本衝突…）：一起回滾，鍵不保留，使用者修正後可以用同一個鍵重送
 *
 * 這個類別本身不開交易：新增全新代號時，TradeService 要在交易外查官方（F-01）。
 */
@Service
public class IdempotencyService {

	private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

	// V6 的約束名稱；H2 的錯誤訊息裡是大寫
	private static final String UNIQUE_KEY_CONSTRAINT = "UK_IDEMPOTENCY_RECORD_KEY";

	private final IdempotencyRecordRepository repository;
	private final Clock clock;
	private final Duration retention;

	public IdempotencyService(IdempotencyRecordRepository repository, Clock clock, IdempotencyProperties properties) {
		this.repository = repository;
		this.clock = clock;
		this.retention = properties.retention();
	}

	/**
	 * 用冪等鍵執行一次寫入。第 6 階段的用法（controller）：
	 * {@code execute(request, trade -> new StoredResponse(201, json(trade)), hook -> tradeService.addTrade(..., hook))}
	 *
	 * @param toResponse 寫入成功後把結果轉成要存的回應；在同一個資料庫交易裡呼叫，需要時可以再查資料庫
	 * @param write      實際的寫入：把拿到的 hook 傳給 TradeService
	 * @return 這次（或上次）的回應
	 * @throws IdempotencyKeyReusedException 同一個鍵已用在另一個 API 或不同的內容
	 * @throws RequestInProgressException    同一個鍵的第一個請求還沒處理完
	 */
	public StoredResponse execute(IdempotencyRequest request, Function<Trade, StoredResponse> toResponse,
			Consumer<TradeWriteHook> write) {
		// 1. 先查：已經完成的直接回傳，不再處理（新增全新代號時也不會再查一次官方）
		IdempotencyRecord existing = repository.findByIdempotencyKey(request.key()).orElse(null);
		if (existing != null) {
			return replay(existing, request);
		}

		RecordingHook hook = new RecordingHook(request, toResponse);
		try {
			write.accept(hook);
		} catch (KeyClaimFailedException e) {
			// 2. 寫鍵時被擋：同一個鍵有另一個請求。那個請求已經 commit 就照它的結果；還沒 commit 就是處理中
			log.info("冪等鍵 {} 已被另一個請求使用（{}）", request.key(), e.getCause().getClass().getName());
			return repository.findByIdempotencyKey(request.key())
					.map(record -> replay(record, request))
					.orElseThrow(RequestInProgressException::new);
		}
		if (hook.response == null) {
			// TradeService 的每一種寫入都會呼叫 afterWrite；走到這裡代表程式寫錯，不能當成成功
			throw new IllegalStateException("寫入完成卻沒有呼叫 afterWrite，無法存下回應");
		}
		return hook.response;
	}

	/** 刪除超過保留期限的紀錄（排程呼叫）。 */
	@Transactional
	public int purgeExpired() {
		LocalDateTime cutoff = LocalDateTime.now(clock).minus(retention);
		return repository.deleteCreatedBefore(cutoff);
	}

	/** 已存在的紀錄：API 和內容都相同才回傳上次的結果。 */
	private static StoredResponse replay(IdempotencyRecord record, IdempotencyRequest request) {
		if (!record.getApi().equals(request.api()) || !record.getRequestHash().equals(request.requestHash())) {
			throw new IdempotencyKeyReusedException();
		}
		// 正常情況別的連線只看得到 COMPLETED（鍵和交易一起 commit）；保險起見，讀到處理中也當成處理中
		if (record.getStatus() != IdempotencyStatus.COMPLETED) {
			throw new RequestInProgressException();
		}
		return new StoredResponse(record.getResponseStatus(), record.getResponseBody());
	}

	/** 在 TradeService 的資料庫交易裡寫冪等鍵紀錄：開頭寫入「處理中」，寫入交易後改成「完成」並存下回應。 */
	private final class RecordingHook implements TradeWriteHook {

		private final IdempotencyRequest request;
		private final Function<Trade, StoredResponse> toResponse;
		// 這一段資料庫交易寫入的紀錄；新增重試時每段交易都是新的一筆
		private IdempotencyRecord record;
		private StoredResponse response;

		RecordingHook(IdempotencyRequest request, Function<Trade, StoredResponse> toResponse) {
			this.request = request;
			this.toResponse = toResponse;
		}

		@Override
		public void beforeLock() {
			IdempotencyRecord inProgress = IdempotencyRecord.inProgress(request.key(), request.api(),
					request.requestHash(), LocalDateTime.now(clock));
			try {
				// saveAndFlush：立刻送出 INSERT，同一個鍵被擋下時在這裡就知道，不會等到 commit
				record = repository.saveAndFlush(inProgress);
			} catch (DataIntegrityViolationException e) {
				if (!isUniqueKeyViolation(e)) {
					throw e;
				}
				// 對方已經 commit（或資料庫不等鎖、直接報重複）
				throw new KeyClaimFailedException(e);
			} catch (PessimisticLockingFailureException e) {
				// 對方還沒 commit，資料庫等它等到逾時
				throw new KeyClaimFailedException(e);
			}
		}

		@Override
		public void afterWrite(Trade trade) {
			StoredResponse result = toResponse.apply(trade);
			record.complete(result.status(), result.body());
			repository.save(record);
			// 先記下來，等 TradeService 的交易 commit 後才由 execute 回傳；commit 失敗時例外會蓋過它
			response = result;
		}
	}

	private static boolean isUniqueKeyViolation(DataIntegrityViolationException e) {
		String message = e.getMostSpecificCause().getMessage();
		return message != null && message.toUpperCase(Locale.ROOT).contains(UNIQUE_KEY_CONSTRAINT);
	}

	/** 寫鍵時被同一個鍵的另一個請求擋下。只在這個類別內使用：丟出去讓 TradeService 的交易回滾，再由 execute 接住。 */
	private static final class KeyClaimFailedException extends RuntimeException {
		KeyClaimFailedException(Throwable cause) {
			super(cause);
		}
	}
}
