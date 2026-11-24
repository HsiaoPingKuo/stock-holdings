package io.hsiaopingkuo.stockholdings.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.hsiaopingkuo.stockholdings.calc.DividendEventInput;

/**
 * 第 3 階段的除權息提供者：還沒有除權息資料，一律回傳空的。
 * 第 4 階段（除權息抓取）完成後換成讀資料庫的實作，這個類別就可以刪掉。
 */
@Component
public class NoDividendEventProvider implements DividendEventProvider {

	@Override
	public Map<String, List<DividendEventInput>> eventsByCode(Collection<String> codes) {
		return Map.of();
	}
}
