package io.hsiaopingkuo.stockholdings.controller;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import io.hsiaopingkuo.stockholdings.calc.TradeSide;
import io.hsiaopingkuo.stockholdings.config.CsrfDeniedHandler;
import io.hsiaopingkuo.stockholdings.dto.ApiV2SummaryResponse;
import io.hsiaopingkuo.stockholdings.dto.DividendRow;
import io.hsiaopingkuo.stockholdings.dto.HoldingPageRow;
import io.hsiaopingkuo.stockholdings.dto.HoldingV2View;
import io.hsiaopingkuo.stockholdings.dto.IdempotencyRequest;
import io.hsiaopingkuo.stockholdings.dto.StoredResponse;
import io.hsiaopingkuo.stockholdings.dto.TradeCreateForm;
import io.hsiaopingkuo.stockholdings.dto.TradeUpdateForm;
import io.hsiaopingkuo.stockholdings.exception.DuplicateCodeException;
import io.hsiaopingkuo.stockholdings.exception.HoldingBusyException;
import io.hsiaopingkuo.stockholdings.exception.HoldingNotFoundException;
import io.hsiaopingkuo.stockholdings.exception.IdempotencyKeyReusedException;
import io.hsiaopingkuo.stockholdings.exception.MarketAmbiguousException;
import io.hsiaopingkuo.stockholdings.exception.MarketUnavailableException;
import io.hsiaopingkuo.stockholdings.exception.RequestInProgressException;
import io.hsiaopingkuo.stockholdings.exception.StockCodeNotFoundException;
import io.hsiaopingkuo.stockholdings.exception.TradeNotFoundException;
import io.hsiaopingkuo.stockholdings.exception.TradeOversellException;
import io.hsiaopingkuo.stockholdings.exception.TradeValidationException;
import io.hsiaopingkuo.stockholdings.exception.VersionConflictException;
import io.hsiaopingkuo.stockholdings.service.HoldingService;
import io.hsiaopingkuo.stockholdings.service.HoldingV2QueryService;
import io.hsiaopingkuo.stockholdings.service.IdempotencyService;
import io.hsiaopingkuo.stockholdings.service.TradeRules;
import io.hsiaopingkuo.stockholdings.service.TradeService;
import jakarta.validation.Valid;

/**
 * 2.0 網頁（第 6 題）：持股清單、單筆明細、交易的新增、修改、刪除。取代 1.0 的 HoldingController。
 * 數字和 /api/v2 用同一套（HoldingV2QueryService、ApiV2HoldingResponse），網頁和 App 2.0 看到的一定相同。
 *
 * - HTML 表單只能送 GET/POST，所以修改、刪除都用 POST 到不同路徑
 * - 成功後一律 redirect（PRG 模式），按 F5 不會重送表單
 * - 每個表單帶一次性編號（requestId），當成 Idempotency-Key 交給 IdempotencyService：
 *   連按兩下送出只處理一次，第二次直接導到第一次的結果頁
 * - 錯誤都顯示在畫面上，不出現錯誤頁
 */
@Controller
public class HoldingWebController {

	static final String ALREADY_SUBMITTED_MESSAGE = "這個表單已經送出過，畫面已重新整理，請確認後再操作";

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	// V6 idempotency_key 欄位的長度
	private static final int MAX_REQUEST_ID_LENGTH = 64;

	// 冪等鍵紀錄的 api 欄位：前面加 WEB，和 /api/v2 的分開，同一個鍵換到另一邊用會被當成重複使用
	private static final String API_CREATE = "WEB POST /holdings/trades";
	private static final String API_UPDATE = "WEB POST /holdings/{id}/trades/{tradeId}";
	private static final String API_DELETE = "WEB POST /holdings/{id}/trades/{tradeId}/delete";

	private final HoldingV2QueryService queryService;
	private final TradeService tradeService;
	private final IdempotencyService idempotencyService;
	private final Clock clock;

	public HoldingWebController(HoldingV2QueryService queryService, TradeService tradeService,
			IdempotencyService idempotencyService, Clock clock) {
		this.queryService = queryService;
		this.tradeService = tradeService;
		this.idempotencyService = idempotencyService;
		this.clock = clock;
	}

	// 所有文字欄位綁定前先去掉前後空白；只有空白時變成 null，交給 @NotBlank 顯示「請輸入」
	@InitBinder
	void trimStrings(WebDataBinder binder) {
		binder.registerCustomEditor(String.class, new StringTrimmerEditor(true));
	}

	// ===== 清單 =====

	/** 首頁和 /holdings 都是清單頁（第 6 題：取代 1.0 網頁；第 1 題的標題和台北時間放在頁首）。 */
	@GetMapping({ "/", "/holdings" })
	public String list(Model model) {
		TradeCreateForm form = new TradeCreateForm();
		form.setRequestId(newRequestId());
		model.addAttribute("createForm", form);
		return showList(model);
	}

	@PostMapping("/holdings/trades")
	public String create(@Valid @ModelAttribute("createForm") TradeCreateForm form, BindingResult bindingResult,
			Model model, RedirectAttributes redirectAttributes) {
		if (bindingResult.hasErrors()) {
			return showList(model);
		}
		IdempotencyRequest request = idempotencyRequest(form.getRequestId(), API_CREATE,
				API_CREATE + "\n" + form.canonical());
		if (request == null) {
			return expired("/holdings", redirectAttributes);
		}
		String code = HoldingService.normalizeCode(form.getCode());
		try {
			StoredResponse response = idempotencyService.execute(request,
					trade -> done("/holdings", "已新增 " + code + " " + sideLabel(form.getSide()) + " "
							+ formatShares(form.getShares()) + " 股"),
					hook -> tradeService.addTrade(form.getCode(), form.getSide(), form.getDate(), form.getShares(),
							form.getPrice(), hook));
			return redirectTo(response, redirectAttributes);
		} catch (TradeValidationException e) {
			// 欄位名稱（code、side、date、shares、price）和表單相同，錯誤直接標在那個欄位
			bindingResult.rejectValue(e.getField(), "invalid", e.getMessage());
		} catch (TradeOversellException e) {
			if (e.getCause() == null) {
				// 只有「代號沒有持股就賣出」沒有 cause（TradeOversellException.noHolding）：問題在代號，標在代號欄位
				bindingResult.rejectValue("code", "noHolding", e.getMessage());
			} else {
				bindingResult.reject("oversell", e.getMessage());
				// 清單頁沒有交易清單，給一個連結到明細頁，標出是哪幾筆
				model.addAttribute("oversellCode", code);
				model.addAttribute("oversellMarks", marksOf(e));
			}
		} catch (StockCodeNotFoundException | MarketUnavailableException | MarketAmbiguousException
				| DuplicateCodeException e) {
			// 全新代號查官方的結果（查不到、連不上、無法判斷市場）：問題在代號，標在代號欄位；表單內容保留，不用重打
			bindingResult.rejectValue("code", "code", e.getMessage());
		} catch (HoldingBusyException | RequestInProgressException e) {
			// 別的裝置正在改同一檔，或第一次送出還在處理中：表單內容保留，稍後再按一次（用同一個編號，不會重複新增）
			bindingResult.reject("busy", e.getMessage());
		} catch (IdempotencyKeyReusedException e) {
			return alreadySubmitted("/holdings", redirectAttributes);
		}
		return showList(model);
	}

	private String showList(Model model) {
		// 只讀資料庫（行情快取、交易、除權息），不呼叫官方：官方連不上時清單照常顯示
		List<HoldingV2View> views = queryService.findAll();
		List<HoldingPageRow> rows = views.stream().map(HoldingPageRow::of).toList();
		// 使用者決定：已賣光的排在最後，前面加一列小標題「已賣光」、灰字；查詢已依代號排序，兩組各自保持代號順序
		List<HoldingPageRow> ordered = new ArrayList<>(rows.stream().filter(r -> !r.soldOut()).toList());
		List<HoldingPageRow> soldOut = rows.stream().filter(HoldingPageRow::soldOut).toList();
		ordered.addAll(soldOut);
		model.addAttribute("holdings", ordered);
		// 第一筆已賣光的位置：畫面在這一列前面插入小標題
		model.addAttribute("soldOutStart", ordered.size() - soldOut.size());
		// 總計和 /api/v2/summary 同一份算法，網頁和 App 2.0 的總計一定相同
		ApiV2SummaryResponse summary = ApiV2SummaryResponse.from(views);
		model.addAttribute("summary", summary);
		// 第 4 題的總計收益率：在網頁這一層算，/api/v2/summary 的 JSON 欄位不變
		model.addAttribute("summaryReturnRate", HoldingPageRow.summaryReturnRate(summary, rows));
		Object oversellCode = model.getAttribute("oversellCode");
		if (oversellCode != null) {
			rows.stream().filter(r -> r.numbers().code().equals(oversellCode)).findFirst()
					.ifPresent(r -> model.addAttribute("oversellHoldingId", r.numbers().id()));
		}
		model.addAttribute("now", LocalDateTime.now(clock).format(TIME_FORMAT));
		// 給頁面上的 JS 當基準，讓時間以「伺服器時間」為準每秒往前走（同第 1 題）
		model.addAttribute("nowEpochMillis", clock.millis());
		addDateRange(model);
		return "holdings";
	}

	// ===== 明細 =====

	/**
	 * 單筆明細。
	 *
	 * @param edit 正在修改的交易 id：那一列變成輸入框
	 * @param mark 要標出來的交易 id（從清單頁新增時超賣，連結帶過來）
	 */
	@GetMapping("/holdings/{id}")
	public String detail(@PathVariable Long id, @RequestParam(required = false) Long edit,
			@RequestParam(required = false) List<Long> mark, Model model) {
		HoldingV2View view = queryService.find(id);
		if (edit != null) {
			view.trades().stream().filter(t -> t.getId().equals(edit)).findFirst().ifPresent(t -> {
				model.addAttribute("editTradeId", edit);
				model.addAttribute("updateForm", TradeUpdateForm.of(t, newRequestId()));
			});
		}
		return showDetail(view, mark, model);
	}

	@PostMapping("/holdings/{id}/trades/{tradeId}")
	public String update(@PathVariable Long id, @PathVariable Long tradeId,
			@Valid @ModelAttribute("updateForm") TradeUpdateForm form, BindingResult bindingResult, Model model,
			RedirectAttributes redirectAttributes) {
		String self = "/holdings/" + id;
		List<Long> marks = List.of();
		if (!bindingResult.hasErrors()) {
			IdempotencyRequest request = idempotencyRequest(form.getRequestId(), API_UPDATE,
					API_UPDATE + "\n" + id + "/" + tradeId + "\n" + form.canonical());
			if (request == null) {
				return expired(self, redirectAttributes);
			}
			try {
				StoredResponse response = idempotencyService.execute(request,
						trade -> done("/holdings/" + trade.getHoldingId(), "已修改 " + trade.getTradeDate() + " 的"
								+ sideLabel(trade.getSide()) + " " + formatShares(trade.getShares()) + " 股"),
						hook -> tradeService.updateTrade(tradeId, form.getVersion(), form.getDate(), form.getShares(),
								form.getPrice(), hook));
				return redirectTo(response, redirectAttributes);
			} catch (TradeValidationException e) {
				bindingResult.rejectValue(e.getField(), "invalid", e.getMessage());
			} catch (TradeOversellException e) {
				// 完整訊息顯示在頁首，交易清單標出超賣的賣出和擋住它的推估買進
				model.addAttribute("errorMessage", e.getMessage());
				marks = marksOf(e);
			} catch (HoldingBusyException | RequestInProgressException e) {
				// 輸入的內容保留，稍後再按一次
				model.addAttribute("errorMessage", e.getMessage());
			} catch (VersionConflictException | TradeNotFoundException e) {
				// 畫面上的資料已經舊了：重新讀一次明細，請使用者看過最新的內容再改
				redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
				return "redirect:" + self;
			} catch (IdempotencyKeyReusedException e) {
				return alreadySubmitted(self, redirectAttributes);
			}
		}
		model.addAttribute("editTradeId", tradeId);
		return showDetail(queryService.find(id), marks, model);
	}

	/** 刪除：DELETE 不帶內容的慣例同 /api/v2，版本號和一次性編號放在表單的隱藏欄位。 */
	@PostMapping("/holdings/{id}/trades/{tradeId}/delete")
	public String delete(@PathVariable Long id, @PathVariable Long tradeId,
			@RequestParam(required = false) Long version, @RequestParam(required = false) String requestId,
			Model model, RedirectAttributes redirectAttributes) {
		String self = "/holdings/" + id;
		IdempotencyRequest request = version == null ? null
				: idempotencyRequest(requestId, API_DELETE, API_DELETE + "\n" + id + "/" + tradeId + "\nversion="
						+ version);
		if (request == null) {
			return expired(self, redirectAttributes);
		}
		try {
			StoredResponse response = idempotencyService.execute(request,
					// 刪掉最後一筆交易時持股會一起刪，明細頁已經不存在，改回清單
					trade -> queryService.findAfterWrite(trade.getHoldingId()).isPresent()
							? done("/holdings/" + trade.getHoldingId(), "已刪除 " + trade.getTradeDate() + " 的"
									+ sideLabel(trade.getSide()) + " " + formatShares(trade.getShares()) + " 股")
							: done("/holdings", "已刪除最後一筆交易，這檔持股已從清單移除"),
					hook -> tradeService.deleteTrade(tradeId, version, hook));
			return redirectTo(response, redirectAttributes);
		} catch (TradeOversellException e) {
			model.addAttribute("errorMessage", e.getMessage());
			return showDetail(queryService.find(id), marksOf(e), model);
		} catch (HoldingBusyException | RequestInProgressException e) {
			model.addAttribute("errorMessage", e.getMessage());
			return showDetail(queryService.find(id), List.of(), model);
		} catch (VersionConflictException | TradeNotFoundException e) {
			redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
			return "redirect:" + self;
		} catch (IdempotencyKeyReusedException e) {
			return alreadySubmitted(self, redirectAttributes);
		}
	}

	private String showDetail(HoldingV2View view, List<Long> marks, Model model) {
		model.addAttribute("h", HoldingPageRow.of(view));
		model.addAttribute("trades", view.trades());
		model.addAttribute("dividends",
				view.dataError() ? List.of() : view.replay().dividends().stream().map(DividendRow::new).toList());
		model.addAttribute("marks", marks == null ? List.of() : marks);
		// 刪除表單共用一個一次性編號：每次畫面重新產生都換新的；刪不同筆時內容不同，不會互相影響
		model.addAttribute("deleteRequestId", newRequestId());
		addDateRange(model);
		return "holding-detail";
	}

	/**
	 * 日期欄位可以選的範圍（和 TradeRules 相同）：2021-01-01 到今天。
	 * 只是讓選擇器不能選到範圍外的日期，送出後伺服器仍會檢查（週末選擇器擋不了，也是由伺服器擋）。
	 * 「今天」用注入的台北時區 Clock 算，不看使用者電腦的時區，人在國外也不會差一天。
	 */
	private void addDateRange(Model model) {
		model.addAttribute("minDate", TradeRules.EARLIEST_DATE);
		model.addAttribute("maxDate", LocalDate.now(clock));
	}

	// 例如在另一個分頁已經刪掉最後一筆交易，這裡又打開明細：回清單並顯示訊息，不出現錯誤頁
	@ExceptionHandler(HoldingNotFoundException.class)
	public String handleNotFound(HoldingNotFoundException e, RedirectAttributes redirectAttributes) {
		redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
		return "redirect:/holdings";
	}

	// ===== 共用 =====

	/**
	 * 一次性編號轉成冪等鍵。沒帶或格式不對（頁面是舊版、被改過）時回 null，由呼叫端請使用者重新整理。
	 * 雜湊的原文含實際的持股、交易 id：同一個編號改送另一筆，會被當成內容不同。
	 */
	private static IdempotencyRequest idempotencyRequest(String requestId, String api, String content) {
		if (requestId == null || requestId.isBlank() || requestId.length() > MAX_REQUEST_ID_LENGTH) {
			return null;
		}
		return new IdempotencyRequest(requestId, api, IdempotencyRequest.sha256(content));
	}

	/**
	 * 存進冪等鍵紀錄的「回應」：網頁要的是導去哪一頁、顯示什麼訊息（用換行分開）。
	 * 狀態碼 303（See Other）只是記錄用，代表「請改去看另一頁」。
	 */
	private static StoredResponse done(String target, String message) {
		return new StoredResponse(303, target + "\n" + message);
	}

	/** 第一次和重送都走這裡：照存下來的結果導頁、顯示同一句訊息。 */
	private static String redirectTo(StoredResponse response, RedirectAttributes redirectAttributes) {
		String[] parts = response.body().split("\n", 2);
		redirectAttributes.addFlashAttribute("message", parts[1]);
		return "redirect:" + parts[0];
	}

	private static String expired(String target, RedirectAttributes redirectAttributes) {
		redirectAttributes.addFlashAttribute("errorMessage", CsrfDeniedHandler.MESSAGE);
		return "redirect:" + target;
	}

	// 同一個編號已經用在另一個內容（例如按瀏覽器上一頁、改了內容再送）：重新整理，拿新的編號
	private static String alreadySubmitted(String target, RedirectAttributes redirectAttributes) {
		redirectAttributes.addFlashAttribute("errorMessage", ALREADY_SUBMITTED_MESSAGE);
		return "redirect:" + target;
	}

	/** 超賣時要在交易清單標出來的交易：超賣的那一筆賣出、擋住它的推估買進（沒有的不列）。 */
	private static List<Long> marksOf(TradeOversellException e) {
		List<Long> marks = new ArrayList<>();
		marks.add(e.getOversoldTradeId());
		marks.add(e.getEstimatedBuyTradeId());
		return marks.stream().filter(Objects::nonNull).toList();
	}

	private static String newRequestId() {
		return UUID.randomUUID().toString();
	}

	private static String sideLabel(TradeSide side) {
		return side == TradeSide.BUY ? "買進" : "賣出";
	}

	// 千分位：Locale.ROOT 讓分隔符號固定是逗號
	private static String formatShares(long shares) {
		return String.format(Locale.ROOT, "%,d", shares);
	}
}
