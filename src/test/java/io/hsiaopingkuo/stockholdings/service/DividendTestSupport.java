package io.hsiaopingkuo.stockholdings.service;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.hsiaopingkuo.stockholdings.calc.TradeSide;
import io.hsiaopingkuo.stockholdings.entity.Holding;
import io.hsiaopingkuo.stockholdings.entity.Market;
import io.hsiaopingkuo.stockholdings.entity.Trade;
import io.hsiaopingkuo.stockholdings.quote.DividendDetailClient;
import io.hsiaopingkuo.stockholdings.quote.DividendDetailResult;
import io.hsiaopingkuo.stockholdings.quote.DividendListClient;
import io.hsiaopingkuo.stockholdings.quote.DividendListResult;
import io.hsiaopingkuo.stockholdings.quote.DividendListRow;
import io.hsiaopingkuo.stockholdings.quote.TpexDividendListClient;
import io.hsiaopingkuo.stockholdings.quote.TwseDividendDetailClient;
import io.hsiaopingkuo.stockholdings.quote.TwseDividendListClient;
import io.hsiaopingkuo.stockholdings.repository.HoldingRepository;
import io.hsiaopingkuo.stockholdings.repository.TradeRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * 除權息同步相關測試共用的假物件和實測資料（第 6 題第 4b 階段）。
 * 實測 JSON（2026-11-25 存下）用 4a 真正的 client 加 MockRestServiceServer 解析，不連網，
 * 測試資料和正式執行時的解析結果完全一樣。
 */
final class DividendTestSupport {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static List<DividendListRow> twseRows2025;
	private static List<DividendListRow> tpexRows2025;
	private static DividendDetailResult esunDetail;

	private DividendTestSupport() {
	}

	/** 上市列表 2025-06～07（673 列，含 2330 息、2884 權息）。 */
	static synchronized List<DividendListRow> twseRows2025() {
		if (twseRows2025 == null) {
			twseRows2025 = new TwseDividendListClient(restClientReturning("dividends/twse-twt49u-20250601-20250731.json"),
					JSON).fetchRange(LocalDate.of(2025, 6, 1), LocalDate.of(2025, 7, 31)).rows();
		}
		return twseRows2025;
	}

	/** 上櫃列表 2025-06～07（481 列，含 8069 除息 4.99013930）。 */
	static synchronized List<DividendListRow> tpexRows2025() {
		if (tpexRows2025 == null) {
			tpexRows2025 = new TpexDividendListClient(restClientReturning("dividends/tpex-ex-daily-q-20250601-20250731.json"),
					JSON).fetchRange(LocalDate.of(2025, 6, 1), LocalDate.of(2025, 7, 31)).rows();
		}
		return tpexRows2025;
	}

	/** 上市明細 2884 2025-07-22（現金 1.2 元、每千股配 10 股）。 */
	static synchronized DividendDetailResult esunDetail() {
		if (esunDetail == null) {
			esunDetail = new TwseDividendDetailClient(restClientReturning("dividends/twse-twt49u-detail-2884-20250722.json"),
					JSON).fetchDetail("2884", LocalDate.of(2025, 7, 22));
		}
		return esunDetail;
	}

	/** 列表裡某一檔的那一列。 */
	static DividendListRow rowOf(List<DividendListRow> rows, String code) {
		return rows.stream().filter(row -> row.code().equals(code)).findFirst().orElseThrow();
	}

	private static RestClient restClientReturning(String resource) {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://example.test");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(anything()).andRespond(withSuccess(new ClassPathResource(resource), MediaType.APPLICATION_JSON));
		return builder.build();
	}

	/** 存一檔持股和一筆買進，回傳持股。 */
	static Holding holdWithBuy(HoldingRepository holdingRepository, TradeRepository tradeRepository, String code,
			Market market, LocalDate date, long shares, String price) {
		Holding holding = holdingRepository.saveAndFlush(new Holding(code, null, market, date.atTime(14, 0)));
		addTrade(tradeRepository, holding, TradeSide.BUY, date, shares, price);
		return holding;
	}

	static void addTrade(TradeRepository tradeRepository, Holding holding, TradeSide side, LocalDate date, long shares,
			String price) {
		LocalDateTime now = date.atTime(14, 0);
		tradeRepository.saveAndFlush(new Trade(holding.getId(), side, date, shares, new BigDecimal(price), false, now));
	}

	/** 一次查詢列表的範圍。 */
	record Range(LocalDate start, LocalDate end) {
	}

	/** 假的列表 client：依年份回結果，並記下查過哪些範圍。 */
	static final class FakeListClient implements DividendListClient {
		private final Market market;
		IntFunction<DividendListResult> answer;
		final List<Range> asked = new ArrayList<>();

		FakeListClient(Market market, IntFunction<DividendListResult> answer) {
			this.market = market;
			this.answer = answer;
		}

		@Override
		public Market market() {
			return market;
		}

		@Override
		public DividendListResult fetchRange(LocalDate start, LocalDate end) {
			asked.add(new Range(start, end));
			return answer.apply(start.getYear());
		}
	}

	/** 假的上市明細 client：依代號回結果，並記下查過哪些代號。 */
	static final class FakeDetailClient implements DividendDetailClient {
		Function<String, DividendDetailResult> answer;
		final List<String> asked = new ArrayList<>();

		FakeDetailClient(Function<String, DividendDetailResult> answer) {
			this.answer = answer;
		}

		@Override
		public DividendDetailResult fetchDetail(String stockNo, LocalDate exDate) {
			asked.add(stockNo);
			return answer.apply(stockNo);
		}
	}
}
