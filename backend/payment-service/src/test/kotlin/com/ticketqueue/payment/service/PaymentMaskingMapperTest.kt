package com.ticketqueue.payment.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.external.portone.PortoneCustomer
import com.ticketqueue.common.external.portone.PortonePaymentAmount
import com.ticketqueue.common.external.portone.PortonePaymentResponse
import com.ticketqueue.common.external.portone.PortoneSelectedChannel
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

@DisplayName("PaymentMaskingMapper 단위 테스트")
class PaymentMaskingMapperTest {

    private val objectMapper: ObjectMapper = ObjectMapper().findAndRegisterModules()
    private val mapper = PaymentMaskingMapper(objectMapper)

    private fun buildJson(method: Map<String, Any?>?): String {
        val response = PortonePaymentResponse(
            id = "payment-id",
            transactionId = "tx-id",
            merchantId = "merchant-id",
            storeId = "store-id",
            status = "PAID",
            amount = PortonePaymentAmount(
                total = 1000L,
                taxFree = 0L,
                discount = 0L,
                paid = 1000L,
                cancelled = 0L,
                cancelledTaxFree = 0L,
            ),
            currency = "KRW",
            channel = PortoneSelectedChannel(
                type = "TEST",
                pgProvider = "test",
                pgMerchantId = "test-mid",
            ),
            version = "v2",
            requestedAt = OffsetDateTime.parse("2026-05-01T10:00:00Z"),
            updatedAt = OffsetDateTime.parse("2026-05-01T10:01:00Z"),
            statusChangedAt = OffsetDateTime.parse("2026-05-01T10:01:00Z"),
            orderName = "order",
            customer = PortoneCustomer(),
            method = method,
            paidAt = OffsetDateTime.parse("2026-05-01T10:01:00Z"),
        )
        return objectMapper.writeValueAsString(response)
    }

    @Nested
    @DisplayName("정상 케이스")
    inner class HappyPath {

        @Test
        fun `method_card_publisher 와 method_card_number 가 모두 있으면 두 필드를 모두 추출한다`() {
            val json = buildJson(mapOf("card" to mapOf("publisher" to "SHINHAN", "number" to "1234-****-****-5678")))

            val result = mapper.extract(json)

            result.name shouldBe "SHINHAN"
            result.number shouldBe "1234-****-****-5678"
        }

        @Test
        fun `method_card_publisher 만 누락되면 number 만 추출한다`() {
            val json = buildJson(mapOf("card" to mapOf("number" to "9999-****-****-1234")))

            val result = mapper.extract(json)

            result.name shouldBe null
            result.number shouldBe "9999-****-****-1234"
        }
    }

    @Nested
    @DisplayName("null-safe 케이스 (silent null)")
    inner class NullSafe {

        @Test
        fun `portoneResponseJson 이 null 이면 빈 CardMeta 를 반환한다`() {
            val result = mapper.extract(null)

            result.name shouldBe null
            result.number shouldBe null
        }

        @Test
        fun `portoneResponseJson 이 빈 문자열이면 빈 CardMeta 를 반환한다`() {
            val result = mapper.extract("")

            result.name shouldBe null
            result.number shouldBe null
        }

        @Test
        fun `method 가 null 이면 빈 CardMeta 를 반환한다`() {
            val json = buildJson(method = null)

            val result = mapper.extract(json)

            result.name shouldBe null
            result.number shouldBe null
        }

        @Test
        fun `method 에 card 키가 없으면 빈 CardMeta 를 반환한다`() {
            val json = buildJson(method = mapOf("type" to "CARD"))

            val result = mapper.extract(json)

            result.name shouldBe null
            result.number shouldBe null
        }

        @Test
        fun `method_card 가 null 이면 빈 CardMeta 를 반환한다`() {
            val json = buildJson(method = mapOf("card" to null))

            val result = mapper.extract(json)

            result.name shouldBe null
            result.number shouldBe null
        }

        @Test
        fun `method_card 가 빈 객체이면 빈 CardMeta 를 반환한다`() {
            val json = buildJson(method = mapOf("card" to emptyMap<String, Any?>()))

            val result = mapper.extract(json)

            result.name shouldBe null
            result.number shouldBe null
        }
    }

    @Nested
    @DisplayName("JSON 파싱 실패")
    inner class JsonError {

        @Test
        fun `깨진 JSON 은 WARN 로그 후 빈 CardMeta 를 반환한다`() {
            val result = mapper.extract("{not-valid-json")

            result.name shouldBe null
            result.number shouldBe null
        }

        @Test
        fun `PortonePaymentResponse 필수 필드 누락 JSON 은 WARN 로그 후 빈 CardMeta 를 반환한다`() {
            val result = mapper.extract("""{"method":{"card":{"publisher":"X","number":"Y"}}}""")

            result.name shouldBe null
            result.number shouldBe null
        }
    }
}
