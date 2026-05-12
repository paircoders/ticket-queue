package com.ticketqueue.payment.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.external.portone.PortoneFeignClient
import com.ticketqueue.common.external.portone.PortoneTokenService
import com.ticketqueue.payment.client.ReservationServiceClient
import com.ticketqueue.payment.entity.PaymentStatus
import com.ticketqueue.payment.repository.PaymentRepository
import io.mockk.every
import io.mockk.justRun
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
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
@DisplayName("결제 요청 API 통합 테스트 (POST /payments)")
class PaymentControllerIntegrationTest {

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

    @MockkBean private lateinit var reservationServiceClient: ReservationServiceClient
    @MockkBean private lateinit var portoneClient: PortoneFeignClient
    @MockkBean private lateinit var portoneTokenService: PortoneTokenService

    private val userId = UUID.randomUUID()
    private val reservationId = UUID.randomUUID()
    private val amount = BigDecimal("300000")

    @BeforeEach
    fun setUp() {
        paymentRepository.deleteAll()
        every { portoneTokenService.getAccessToken() } returns "Bearer test-token"
        justRun { portoneClient.preRegisterPayment(any(), any(), any()) }
        every { reservationServiceClient.getReservation(reservationId) } returns buildReservation()
    }

    private fun buildReservation(
        ownerId: UUID = userId,
        totalAmount: BigDecimal = amount,
        status: String = "PENDING",
        holdExpiresAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5)
    ) = ReservationServiceClient.ReservationDetailResponse(
        reservationId = reservationId,
        userId = ownerId,
        scheduleId = UUID.randomUUID(),
        totalAmount = totalAmount,
        status = status,
        holdExpiresAt = holdExpiresAt,
        seatIds = listOf(UUID.randomUUID())
    )

    private fun createRequestBody(
        reservationId: UUID = this.reservationId,
        amount: BigDecimal = this.amount
    ) = mapOf("reservationId" to reservationId, "amount" to amount, "paymentMethod" to "CARD")

    @Nested
    @DisplayName("정상 요청")
    inner class SuccessCase {

        @Test
        @DisplayName("200 OK와 paymentId, paymentKey, storeId, channelKey를 반환한다")
        fun createPaymentSuccess() {
            mockMvc.perform(
                post("/payments")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(createRequestBody()))
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.paymentId").isNotEmpty)
                .andExpect(jsonPath("$.amount").value(300000))
                .andExpect(jsonPath("$.storeId").value("test-store-id"))
                .andExpect(jsonPath("$.channelKey").value("test-channel-key"))
                .andExpect(jsonPath("$.paymentKey").isNotEmpty)
        }

        @Test
        @DisplayName("Payment 엔티티가 DB에 PENDING 상태로 저장된다")
        fun paymentPersistedAsPending() {
            mockMvc.perform(
                post("/payments")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(createRequestBody()))
            ).andExpect(status().isOk)

            val payments = paymentRepository.findAll()
            assert(payments.size == 1)
            assert(payments[0].status == PaymentStatus.PENDING)
            assert(payments[0].userId == userId)
            assert(payments[0].reservationId == reservationId)
        }
    }

    @Nested
    @DisplayName("입력 검증 실패")
    inner class ValidationFailure {

        @Test
        @DisplayName("X-User-Id 헤더 없으면 401 반환")
        fun missingUserId() {
            mockMvc.perform(
                post("/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(createRequestBody()))
            ).andExpect(status().isUnauthorized)
        }

        @Test
        @DisplayName("amount가 음수면 400 반환")
        fun negativeAmount() {
            mockMvc.perform(
                post("/payments")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(createRequestBody(amount = BigDecimal("-1"))))
            ).andExpect(status().isBadRequest)
        }

        @Test
        @DisplayName("reservationId 누락 시 400 반환")
        fun missingReservationId() {
            val body = mapOf("amount" to amount, "paymentMethod" to "CARD")
            mockMvc.perform(
                post("/payments")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body))
            ).andExpect(status().isBadRequest)
        }
    }

    @Nested
    @DisplayName("비즈니스 검증 실패")
    inner class BusinessFailure {

        @Test
        @DisplayName("요청 금액과 예매 금액 불일치 시 400 반환")
        fun amountMismatch() {
            every { reservationServiceClient.getReservation(reservationId) } returns
                buildReservation(totalAmount = BigDecimal("200000"))

            mockMvc.perform(
                post("/payments")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(createRequestBody()))
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value(ErrorCode.PAYMENT_AMOUNT_MISMATCH.code))
        }

        @Test
        @DisplayName("타인의 예매 결제 시도 시 403 반환")
        fun forbidden() {
            every { reservationServiceClient.getReservation(reservationId) } returns
                buildReservation(ownerId = UUID.randomUUID())

            mockMvc.perform(
                post("/payments")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(createRequestBody()))
            )
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.code))
        }

        @Test
        @DisplayName("예매 상태가 PENDING이 아니면 410과 HOLD_EXPIRED 반환")
        fun holdExpiredByStatus() {
            every { reservationServiceClient.getReservation(reservationId) } returns
                buildReservation(status = "CONFIRMED")

            mockMvc.perform(
                post("/payments")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(createRequestBody()))
            )
                .andExpect(status().isGone)
                .andExpect(jsonPath("$.code").value(ErrorCode.HOLD_EXPIRED.code))
        }

        @Test
        @DisplayName("holdExpiresAt이 경과하면 410과 HOLD_EXPIRED 반환")
        fun holdExpiredByTime() {
            every { reservationServiceClient.getReservation(reservationId) } returns
                buildReservation(holdExpiresAt = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1))

            mockMvc.perform(
                post("/payments")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(createRequestBody()))
            )
                .andExpect(status().isGone)
                .andExpect(jsonPath("$.code").value(ErrorCode.HOLD_EXPIRED.code))
        }
    }
}
