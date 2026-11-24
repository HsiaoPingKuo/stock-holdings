package io.hsiaopingkuo.stockholdings.controller;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import io.hsiaopingkuo.stockholdings.dto.HoldingCreateForm;
import io.hsiaopingkuo.stockholdings.dto.HoldingUpdateForm;
import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.exception.DuplicateCodeException;
import io.hsiaopingkuo.stockholdings.exception.HoldingNotFoundException;
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
		model.addAttribute("holdings", holdingService.findAll());
		return "holdings";
	}
}
