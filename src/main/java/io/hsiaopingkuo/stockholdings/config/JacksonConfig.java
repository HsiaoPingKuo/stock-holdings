package io.hsiaopingkuo.stockholdings.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * JSON 型別要嚴格（第 5 題）：App 不會先檢查輸入，使用者填什麼就送什麼，
 * Jackson 預設會「好心」自動轉型別，例如張數 1.5 截成 1、字串 "1000" 轉成數字，資料就悄悄被改掉了。
 * 這裡關掉自動轉換，型別不對時讀取失敗，API 回 400 VALIDATION_ERROR。
 *
 * Spring Boot 4 用 Jackson 3（套件 tools.jackson）。「字串轉數字」這類規則叫 coercion，
 * spring.jackson.* 屬性設定不到，所以用 JsonMapperBuilderCustomizer 調整 Spring Boot 建好的 JsonMapper。
 *
 * 影響範圍：只有「把 JSON 讀成 Java 物件」時才會套用（目前就是 App API 的請求內容）。
 * 網頁表單不經過 Jackson；行情 client 用 readTree 讀成樹狀結構，也不會套用這些規則。
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

	@Bean
	JsonMapperBuilderCustomizer strictTypeCoercion() {
		return builder -> builder
				// 整數欄位（例如張數）：只接受 JSON 整數。1.5、1.0、"1"、true 都失敗
				.withCoercionConfig(LogicalType.Integer, config -> config
						.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
						.setCoercion(CoercionInputShape.String, CoercionAction.Fail)
						.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
						.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
				// 小數欄位（BigDecimal 屬於這一類，例如買進價）：接受 JSON 數字（1000 和 980.5 都可以），
				// 字串 "1000"、true 失敗
				.withCoercionConfig(LogicalType.Float, config -> config
						.setCoercion(CoercionInputShape.String, CoercionAction.Fail)
						.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
						.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
				// 文字欄位（例如代號）：只接受 JSON 字串。數字 2330、true 失敗
				.withCoercionConfig(LogicalType.Textual, config -> config
						.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
						.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
						.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
	}
}
