package com.ticketqueue.payment.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.external.portone.PortonePaymentResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * PortOne 결제 응답(jsonb) 에서 카드 발급사명과 마스킹 카드번호를 추출한다.
 *
 * Hybrid 정책 (Plan §7 Decision 1):
 *  - envelope: [PortonePaymentResponse] 로 typed deserialize (JacksonConfig 의 FAIL_ON_UNKNOWN_PROPERTIES=false 로 신규 필드 허용)
 *  - nested: `method["card"]` 는 `Map<String, Any?>` 로 generic null-safe cast — PortOne 카드 메타 키가 결제수단별로 비정형이라 fail-fast 대신 silent null 로 회복력 확보
 *
 * envelope 단계 실패 (PortOne 응답 schema 가 envelope 레벨에서 breaking change) 시 WARN 로그 후 빈 [CardMeta] 반환. 예외는 전파하지 않는다.
 */
@Component
class PaymentMaskingMapper(
    private val objectMapper: ObjectMapper,
) {
    private val log = KotlinLogging.logger {}

    fun extract(portoneResponseJson: String?): CardMeta {
        if (portoneResponseJson.isNullOrBlank()) return CardMeta(null, null)
        return try {
            val response = objectMapper.readValue(portoneResponseJson, PortonePaymentResponse::class.java)
            val card = response.method?.get("card") as? Map<*, *>
            CardMeta(
                name = card?.get("publisher") as? String,
                number = card?.get("number") as? String,
            )
        } catch (e: JsonProcessingException) {
            log.warn { "Failed to deserialize PortOne payment response for card meta extraction: ${e.message}" }
            CardMeta(null, null)
        }
    }

    data class CardMeta(val name: String?, val number: String?)
}
