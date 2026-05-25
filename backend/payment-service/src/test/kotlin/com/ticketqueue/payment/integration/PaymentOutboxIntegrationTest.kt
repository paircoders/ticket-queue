package com.ticketqueue.payment.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.ninjasquad.springmockk.MockkBean
import com.ticketqueue.common.event.PaymentFailedEvent
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.external.portone.PortoneFeignClient
import com.ticketqueue.common.external.portone.PortoneTokenService
import com.ticketqueue.common.outbox.OutboxEventRepository
import com.ticketqueue.payment.client.ReservationServiceClient
import com.ticketqueue.payment.entity.PaymentStatus
import com.ticketqueue.payment.repository.PaymentRepository
import io.kotest.matchers.shouldBe
import io.mockk.every
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Transactional Outbox 통합 테스트 — PortOne pre-register 실패 경로.
 *
 * 시나리오: POST /payments → reservation 검증 통과 → Payment(PENDING) INSERT →
 * PortOne pre-register stub 이 예외 → 트랜잭션 안에서 markFailed + outbox row INSERT →
 * 502 BAD_GATEWAY 응답.
 *
 * **검증 포인트:**
 * 1. `payment_service.payments` row 의 status = FAILED
 * 2. `common.outbox_events` row 1건 + aggregateType=Payment / eventType=PaymentFailed / published=false
 * 3. row.id == JSON payload.eventId  (#228 OutboxEventRecorder SOT 계약)
 *
 * Poller 는 application-test.yml 에서 비활성화되어 있으므로 published=false 상태로 검증 가능.
 */
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
@DisplayName("Payment Transactional Outbox 통합 테스트 (PortOne 실패 → outbox row)")
class PaymentOutboxIntegrationTest {

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

    @MockkBean private lateinit var reservationServiceClient: ReservationServiceClient
    @MockkBean private lateinit var portoneClient: PortoneFeignClient
    @MockkBean private lateinit var portoneTokenService: PortoneTokenService

    private val userId = UUID.randomUUID()
    private val reservationId = UUID.randomUUID()
    private val amount = BigDecimal("300000")

    @BeforeEach
    fun setUp() {
        outboxEventRepository.deleteAll()
        paymentRepository.deleteAll()
        every { portoneTokenService.getAccessToken() } returns "Bearer test-token"
        every { reservationServiceClient.getReservation(reservationId) } returns
            ReservationServiceClient.ReservationDetailResponse(
                reservationId = reservationId,
                userId = userId,
                scheduleId = UUID.randomUUID(),
                totalAmount = amount,
                status = ReservationServiceClient.ReservationStatus.PENDING,
                holdExpiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5),
                seatIds = listOf(UUID.randomUUID(), UUID.randomUUID())
            )
    }

    @AfterEach
    fun cleanUp() {
        outboxEventRepository.deleteAll()
        paymentRepository.deleteAll()
    }

    @Test
    @DisplayName("PortOne pre-register 실패 시 payment.status=FAILED 와 outbox_events row 1건이 INSERT 된다 (id == payload.eventId)")
    fun portoneFailureInsertsOutboxRowAtomically() {
        every { portoneClient.preRegisterPayment(any(), any(), any()) } throws
            RuntimeException("simulated PortOne 5xx")

        mockMvc.perform(
            post("/payments")
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "reservationId" to reservationId,
                            "amount" to amount,
                            "paymentMethod" to "CARD"
                        )
                    )
                )
        )
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.code").value(ErrorCode.PORTONE_PRE_REGISTER_FAILED.code))

        // 1) payment row 검증
        val payments = paymentRepository.findAll()
        payments.size shouldBe 1
        val payment = payments.single()
        payment.status shouldBe PaymentStatus.FAILED
        payment.reservationId shouldBe reservationId
        payment.userId shouldBe userId

        // 2) outbox row 검증
        val outboxRows = outboxEventRepository.findAll()
        outboxRows.size shouldBe 1
        val row = outboxRows.single()
        row.aggregateType shouldBe "Payment"
        row.eventType shouldBe "PaymentFailed"
        row.aggregateId shouldBe payment.id
        row.published shouldBe false

        // 3) SOT 계약: row.id == payload.eventId
        val payload = objectMapper.readValue<PaymentFailedEvent>(row.payload)
        row.id shouldBe payload.eventId
        payload.aggregateType shouldBe "Payment"
        payload.eventType shouldBe "PaymentFailed"
        payload.reservationId shouldBe reservationId
        payload.metadata.userId shouldBe userId
    }
}
