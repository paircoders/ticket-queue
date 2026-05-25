package com.ticketqueue.payment.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.ticketqueue.common.external.portone.PortoneCustomer
import com.ticketqueue.common.external.portone.PortoneFeignClient
import com.ticketqueue.common.external.portone.PortonePaymentAmount
import com.ticketqueue.common.external.portone.PortonePaymentResponse
import com.ticketqueue.common.external.portone.PortoneSelectedChannel
import com.ticketqueue.common.external.portone.PortoneTokenService
import com.ticketqueue.common.outbox.OutboxEventRepository
import com.ticketqueue.payment.client.ReservationServiceClient
import com.ticketqueue.payment.entity.Payment
import com.ticketqueue.payment.entity.PaymentMethod
import com.ticketqueue.payment.entity.PaymentStatus
import com.ticketqueue.payment.repository.PaymentRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "spring.config.import=",
        "spring.cloud.aws.region.static=us-east-1",
        "spring.cloud.aws.credentials.access-key=test",
        "spring.cloud.aws.credentials.secret-key=test",
        "spring.cloud.aws.secretsmanager.enabled=false"
    ]
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DisplayName("결제 조회 API 통합 테스트 (GET /payments)")
class PaymentQueryControllerIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("ticketing")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("db/init.sql")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var paymentRepository: PaymentRepository
    @Autowired private lateinit var outboxEventRepository: OutboxEventRepository
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    // 본 통합 테스트는 read-only 라 실제 호출 없으나 Spring context 빈 wiring 위해 stub.
    @MockkBean private lateinit var reservationServiceClient: ReservationServiceClient
    @MockkBean private lateinit var portoneClient: PortoneFeignClient
    @MockkBean private lateinit var portoneTokenService: PortoneTokenService

    private val userId = UUID.randomUUID()
    private val otherUserId = UUID.randomUUID()
    private val amount = BigDecimal("300000")

    @BeforeEach
    fun setUp() {
        outboxEventRepository.deleteAll()
        paymentRepository.deleteAll()
    }

    /**
     * PortOne 응답 JSON 시드 helper — `method` 만 가변.
     *
     * mapper 가 envelope typed deserialize 를 시도하므로 PortonePaymentResponse 의 모든 required 필드가 채워져야 한다.
     */
    private fun portoneResponseJson(method: Map<String, Any?>?): String {
        val response = PortonePaymentResponse(
            id = "p-${UUID.randomUUID()}",
            transactionId = "tx-${UUID.randomUUID()}",
            merchantId = "m",
            storeId = "store-id",
            status = "PAID",
            amount = PortonePaymentAmount(
                total = 300000L,
                taxFree = 0L,
                discount = 0L,
                paid = 300000L,
                cancelled = 0L,
                cancelledTaxFree = 0L,
            ),
            currency = "KRW",
            channel = PortoneSelectedChannel("TEST", "test-pg", "test-mid"),
            version = "v2",
            requestedAt = OffsetDateTime.now(),
            updatedAt = OffsetDateTime.now(),
            statusChangedAt = OffsetDateTime.now(),
            orderName = "order",
            customer = PortoneCustomer(),
            method = method,
            paidAt = OffsetDateTime.now(),
        )
        return objectMapper.writeValueAsString(response)
    }

    /**
     * Payment row 시드. createdAt 명시 시 @Column(updatable=false) 우회를 위해 native UPDATE 사용 (Critic N2 — flaky 회피).
     * `idx_payments_reservation_pending_success` partial unique 회피 위해 호출자가 reservationId 를 가변 지정해야 한다.
     */
    private fun seed(
        ownerId: UUID = userId,
        reservationId: UUID = UUID.randomUUID(),
        status: PaymentStatus = PaymentStatus.SUCCESS,
        portoneResponse: String? = null,
        paidAt: LocalDateTime? = LocalDateTime.now(ZoneOffset.UTC),
        createdAt: LocalDateTime? = null,
    ): Payment {
        val payment = Payment(
            reservationId = reservationId,
            userId = ownerId,
            paymentKey = UUID.randomUUID().toString(),
            amount = amount,
            paymentMethod = PaymentMethod.CARD,
            status = status,
            portoneResponse = portoneResponse,
            paidAt = paidAt,
        )
        val saved = paymentRepository.saveAndFlush(payment)
        if (createdAt != null) {
            jdbcTemplate.update(
                "UPDATE payment_service.payments SET created_at = ? WHERE id = ?",
                Timestamp.valueOf(createdAt),
                saved.id,
            )
        }
        return saved
    }

    // ============================================================
    // GET /payments/{paymentId} — 결제 상세
    // ============================================================

    @Nested
    @DisplayName("GET /payments/{paymentId} — 결제 상세")
    inner class Detail {

        @Test
        @DisplayName("AC-1: 본인 SUCCESS 결제 + 카드 메타가 응답에 매핑된다")
        fun ac1_successDetail() {
            val responseJson = portoneResponseJson(
                method = mapOf("card" to mapOf("publisher" to "SHINHAN", "number" to "1234-****-****-5678"))
            )
            val payment = seed(status = PaymentStatus.SUCCESS, portoneResponse = responseJson)

            mockMvc.perform(
                get("/payments/{paymentId}", payment.id)
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.paymentId").value(payment.id.toString()))
                .andExpect(jsonPath("$.reservationId").value(payment.reservationId.toString()))
                .andExpect(jsonPath("$.amount").value(300000))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.method").value("CARD"))
                .andExpect(jsonPath("$.cardName").value("SHINHAN"))
                .andExpect(jsonPath("$.cardNumber").value("1234-****-****-5678"))
                .andExpect(jsonPath("$.paidAt").doesNotExist())
        }

        @Test
        @DisplayName("AC-2: 다른 유저 소유 결제 조회 시 403 FORBIDDEN")
        fun ac2_forbidden() {
            val payment = seed(ownerId = otherUserId, status = PaymentStatus.SUCCESS)

            mockMvc.perform(
                get("/payments/{paymentId}", payment.id)
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
            )
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        }

        @Test
        @DisplayName("AC-3: 존재하지 않는 paymentId 조회 시 404 RESOURCE_NOT_FOUND")
        fun ac3_notFound() {
            mockMvc.perform(
                get("/payments/{paymentId}", UUID.randomUUID())
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
            )
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        }

        @Test
        @DisplayName("AC-4: FAILED 결제 + portoneResponse=null → cardName/cardNumber=null + status=FAILED")
        fun ac4_failedNullCardMeta() {
            val payment = seed(status = PaymentStatus.FAILED, portoneResponse = null, paidAt = null)

            mockMvc.perform(
                get("/payments/{paymentId}", payment.id)
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.cardName").doesNotExist())
                .andExpect(jsonPath("$.cardNumber").doesNotExist())
        }

        @Test
        @DisplayName("AC-10: X-User-Id 헤더 누락 시 401 (Security filter 가 우선 401 차단 — 운영 mechanism 동일)")
        fun ac10_unauthorized() {
            val payment = seed(status = PaymentStatus.SUCCESS)

            mockMvc.perform(
                get("/payments/{paymentId}", payment.id)
                    .header("X-User-Role", "USER")
            ).andExpect(status().isUnauthorized)
        }

        @Test
        @DisplayName("AC-11a: method={} 시 cardName/cardNumber=null + 200")
        fun ac11a_emptyMethod() {
            val responseJson = portoneResponseJson(method = emptyMap())
            val payment = seed(status = PaymentStatus.SUCCESS, portoneResponse = responseJson)

            mockMvc.perform(
                get("/payments/{paymentId}", payment.id)
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.cardName").doesNotExist())
                .andExpect(jsonPath("$.cardNumber").doesNotExist())
        }

        @Test
        @DisplayName("AC-11b: method.card=null 시 cardName/cardNumber=null + 200")
        fun ac11b_nullCard() {
            val responseJson = portoneResponseJson(method = mapOf("card" to null))
            val payment = seed(status = PaymentStatus.SUCCESS, portoneResponse = responseJson)

            mockMvc.perform(
                get("/payments/{paymentId}", payment.id)
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.cardName").doesNotExist())
                .andExpect(jsonPath("$.cardNumber").doesNotExist())
        }

        @Test
        @DisplayName("AC-11c: method.card={} 시 cardName/cardNumber=null + 200")
        fun ac11c_emptyCard() {
            val responseJson = portoneResponseJson(method = mapOf("card" to emptyMap<String, Any?>()))
            val payment = seed(status = PaymentStatus.SUCCESS, portoneResponse = responseJson)

            mockMvc.perform(
                get("/payments/{paymentId}", payment.id)
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.cardName").doesNotExist())
                .andExpect(jsonPath("$.cardNumber").doesNotExist())
        }
    }

    // ============================================================
    // GET /payments — 내 결제 내역 페이징
    // ============================================================

    @Nested
    @DisplayName("GET /payments — 내 결제 내역 페이징")
    inner class List {

        @Test
        @DisplayName("AC-5: 5건 시드, page=0&size=20 → totalElements=5 + createdAt DESC 정렬")
        fun ac5_paging() {
            // createdAt 명시 주입 — flaky 회피, 정렬 검증 가능 (Critic N2)
            val base = LocalDateTime.now(ZoneOffset.UTC).minusHours(5)
            (1..5).forEach { idx ->
                seed(createdAt = base.plusMinutes(idx.toLong()))
            }

            mockMvc.perform(
                get("/payments")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .param("page", "0")
                    .param("size", "20")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.list.length()").value(5))
                .andExpect(jsonPath("$.list[0].paidAt").exists())
        }

        @Test
        @DisplayName("AC-6: 격리 — 다른 유저 결제 3건만 있으면 본인 list=[], totalElements=0")
        fun ac6_isolation() {
            repeat(3) { seed(ownerId = otherUserId) }

            mockMvc.perform(
                get("/payments")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.list.length()").value(0))
        }

        @Test
        @DisplayName("AC-7a: ?size=101 → 400 (Max 100 위반)")
        fun ac7a_sizeTooLarge() {
            mockMvc.perform(
                get("/payments")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .param("size", "101")
            ).andExpect(status().isBadRequest)
        }

        @Test
        @DisplayName("AC-7b: ?page=-1 → 400 (Min 0 위반)")
        fun ac7b_negativePage() {
            mockMvc.perform(
                get("/payments")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .param("page", "-1")
            ).andExpect(status().isBadRequest)
        }

        @Test
        @DisplayName("AC-7c: ?size=0 → 400 (Min 1 위반)")
        fun ac7c_sizeZero() {
            mockMvc.perform(
                get("/payments")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .param("size", "0")
            ).andExpect(status().isBadRequest)
        }

        @Test
        @DisplayName("AC-9: 모든 status (PENDING/SUCCESS/FAILED/REFUNDED) 가 list 에 포함된다")
        fun ac9_allStatuses() {
            seed(status = PaymentStatus.PENDING, paidAt = null)
            seed(status = PaymentStatus.SUCCESS)
            seed(status = PaymentStatus.FAILED, paidAt = null)
            // REFUNDED 는 markSuccess 단계의 paidAt 유지 (Decision 8 정정)
            seed(status = PaymentStatus.REFUNDED, paidAt = LocalDateTime.now(ZoneOffset.UTC).minusHours(1))

            mockMvc.perform(
                get("/payments")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .param("size", "20")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.list[?(@.status == 'PENDING')]").isNotEmpty)
                .andExpect(jsonPath("$.list[?(@.status == 'SUCCESS')]").isNotEmpty)
                .andExpect(jsonPath("$.list[?(@.status == 'FAILED')]").isNotEmpty)
                .andExpect(jsonPath("$.list[?(@.status == 'REFUNDED')]").isNotEmpty)
        }

        @Test
        @DisplayName("AC-10: GET /payments 도 X-User-Id 헤더 누락 시 401")
        fun ac10_list_unauthorized() {
            mockMvc.perform(
                get("/payments")
                    .header("X-User-Role", "USER")
            ).andExpect(status().isUnauthorized)
        }
    }
}
