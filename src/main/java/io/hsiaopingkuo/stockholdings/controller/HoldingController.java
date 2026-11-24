package io.hsiaopingkuo.stockholdings.controller;

import java.util.regex.Pattern;

import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import io.hsiaopingkuo.stockholdings.dto.ErrorResponse;
import io.hsiaopingkuo.stockholdings.dto.HoldingCreateForm;
import io.hsiaopingkuo.stockholdings.dto.StockLookupResponse;
import io.hsiaopingkuo.stockholdings.dto.HoldingUpdateForm;
import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.exception.DuplicateCodeException;
import io.hsiaopingkuo.stockholdings.exception.HoldingNotFoundException;
import io.hsiaopingkuo.stockholdings.exception.MarketUnavailableException;
import io.hsiaopingkuo.stockholdings.exception.StockCodeNotFoundException;
import io.hsiaopingkuo.stockholdings.quote.StockLookup;
import io.hsiaopingkuo.stockholdings.service.HoldingService;
import jakarta.validation.Valid;

/**
 * 持股清單頁（第 2 題）：新增、修改、刪除、查詢。
 * HTML 表單只能送 GET/POST，所以修改和刪除都用 POST 到不同路徑。
 * 成功後一律 redirect 回清單（PRG 模式），使用者按 F5 才不會重複送出表單。
 */
@Controller
@RequestMapping("/holdings")
public class HoldingController {

	// 和 HoldingCreateForm 的 @Pattern、@Size 相同：英數字、1～10 個字
	private static final Pattern VALID_CODE = Pattern.compile("^[A-Za-z0-9]{1,10}$");

	private final HoldingService holdingService;

	public HoldingController(HoldingService holdingService) {
		this.holdingService = holdingService;
	}

	// 所有文字欄位綁定前先去掉前後空白；只有空白時變成 null，交給 @NotBlank 顯示「請輸入」
	@InitBinder
	void trimStrings(WebDataBinder binder) {
		binder.registerCustomEditor(String.class, new StringTrimmerEditor(true));
	}

	@GetMapping
	public String list(Model model) {
		model.addAttribute("createForm", new HoldingCreateForm());
		return showList(model);
	}

	@PostMapping
	public String create(@Valid @ModelAttribute("createForm") HoldingCreateForm form, BindingResult bindingResult,
			Model model, RedirectAttributes redirectAttributes) {
		// 欄位檢查沒過：留在原頁，錯誤訊息顯示在表單上，資料不儲存
		if (bindingResult.hasErrors()) {
			return showList(model);
		}
		try {
			Holding created = holdingService.create(form.getCode(), form.getLots(), form.getBuyPrice());
			redirectAttributes.addFlashAttribute("message", "已新增 " + created.getCode());
			return "redirect:/holdings";
		} catch (DuplicateCodeException e) {
			// 重複代號也當成「代號欄位的錯誤」顯示在表單上，不跳錯誤頁
			bindingResult.rejectValue("code", "duplicate", e.getMessage());
			return showList(model);
		} catch (StockCodeNotFoundException e) {
			// 第 3 題：上市、上櫃都查不到，資料不儲存，錯誤顯示在代號欄位
			bindingResult.rejectValue("code", "notFound", e.getMessage());
			return showList(model);
		} catch (MarketUnavailableException e) {
			// 第 3 題：官方連不上時不能出現錯誤頁，請使用者稍後再試；表單內容保留，不用重打
			bindingResult.rejectValue("code", "unavailable", e.getMessage());
			return showList(model);
		}
	}

	/**
	 * 新增表單輸入代號時，欄位旁即時顯示名稱與市場（畫面上的 JavaScript 呼叫）。
	 * 只是提示：按下新增時伺服器仍會重新檢查，不能只相信畫面上的結果。
	 * 回傳 JSON；錯誤格式和專案慣例一致：{"error":{"code":"...","message":"..."}}。
	 */
	@GetMapping(value = "/lookup", produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	public ResponseEntity<Object> lookup(@RequestParam(required = false) String code) {
		// 和新增表單相同的格式規則；不合格的代號直接擋掉，不浪費官方查詢
		if (code == null || !VALID_CODE.matcher(code.trim()).matches()) {
			return ResponseEntity.badRequest()
					.body(ErrorResponse.of("INVALID_CODE", "股票代號只能是英文字母或數字，最多 10 個字"));
		}
		try {
			StockLookup lookup = holdingService.lookupStock(code);
			// 回傳正規化後的代號（例如輸入 00631l 回 00631L），畫面顯示和實際儲存的一致
			return ResponseEntity.ok(StockLookupResponse.of(HoldingService.normalizeCode(code), lookup));
		} catch (StockCodeNotFoundException e) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of("STOCK_NOT_FOUND", e.getMessage()));
		} catch (MarketUnavailableException e) {
			// 503：伺服器本身正常，只是暫時拿不到官方資料
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
					.body(ErrorResponse.of("QUOTE_UNAVAILABLE", e.getMessage()));
		}
	}

	@GetMapping("/{id}/edit")
	public String editForm(@PathVariable Long id, Model model) {
		Holding holding = holdingService.findById(id);
		HoldingUpdateForm form = new HoldingUpdateForm();
		form.setLots(holding.getLots());
		form.setBuyPrice(holding.getBuyPrice());
		model.addAttribute("holding", holding);
		model.addAttribute("updateForm", form);
		return "holding-edit";
	}

	@PostMapping("/{id}")
	public String update(@PathVariable Long id, @Valid @ModelAttribute("updateForm") HoldingUpdateForm form,
			BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
		if (bindingResult.hasErrors()) {
			// 修改頁要顯示代號，所以重新讀一次這筆持股
			model.addAttribute("holding", holdingService.findById(id));
			return "holding-edit";
		}
		Holding updated = holdingService.update(id, form.getLots(), form.getBuyPrice());
		redirectAttributes.addFlashAttribute("message", "已修改 " + updated.getCode());
		return "redirect:/holdings";
	}

	@PostMapping("/{id}/delete")
	public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
		holdingService.delete(id);
		redirectAttributes.addFlashAttribute("message", "已刪除");
		return "redirect:/holdings";
	}

	// 例如在另一個分頁已經刪掉，這裡又按修改或刪除：回清單並顯示訊息，不出現錯誤頁
	@ExceptionHandler(HoldingNotFoundException.class)
	public String handleNotFound(HoldingNotFoundException e, RedirectAttributes redirectAttributes) {
		redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
		return "redirect:/holdings";
	}

	private String showList(Model model) {
		// 第 3 題：收盤價來自資料庫的行情快取，這裡不會呼叫官方；官方連不上時清單照常顯示
		model.addAttribute("holdings", holdingService.findAllForList());
		return "holdings";
	}
}
