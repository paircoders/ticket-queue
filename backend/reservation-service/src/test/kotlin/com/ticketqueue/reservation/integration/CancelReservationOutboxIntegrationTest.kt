package com.ticketqueue.reservation.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.ninjasquad.springmockk.MockkBean
import com.ticketqueue.common.event.ReservationCancelledEvent
import com.ticketqueue.common.outbox.OutboxEventRepository
import com.ticketqueue.reservation.client.EventServiceClient
import com.ticketqueue.reservation.entity.Reservation
import com.ticketqueue.reservation.entity.ReservationSeat
import com.ticketqueue.reservation.entity.ReservationStatus
import com.ticketqueue.reservation.repository.ReservationRepository
import com.ticketqueue.reservation.repository.ReservationSeatRepository
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.GenericContainer
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
@DisplayName("예매 취소 Outbox 통합 테스트 (DELETE /reservations/{id} → outbox row)")
class CancelReservationOutboxIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ticketing")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("db/init.sql")

        @Container
        @JvmStatic
        val valkey = GenericContainer("valkey/valkey:8.1.5-alpine3.23")
            .withExposedPorts(6379)

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.data.redis.host") { valkey.host }
            registry.add("spring.data.redis.port") { valkey.getMappedPort(6379) }
        }
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var reservationRepository: ReservationRepository
    @Autowired private lateinit var reservationSeatRepository: ReservationSeatRepository
    @Autowired private lateinit var outboxEventRepository: OutboxEventRepository

    @MockkBean private lateinit var eventServiceClient: EventServiceClient

    private val userId = UUID.randomUUID()
    private val scheduleId = UUID.randomUUID()
    private val eventId = UUID.randomUUID()
    private val seatIdA = UUID.randomUUID()
    private val seatIdB = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        every { eventServiceClient.getScheduleInfo(scheduleId) } returns
            EventServiceClient.ScheduleInfoResponse(
                scheduleId = scheduleId,
                eventId = eventId,
                eventStartAt = LocalDateTime.now(ZoneOffset.UTC).plusDays(3),
                eventEndAt = LocalDateTime.now(ZoneOffset.UTC).plusDays(3).plusHours(3),
                saleStartAt = LocalDateTime.now(ZoneOffset.UTC).minusDays(7),
                saleEndAt = LocalDateTime.now(ZoneOffset.UTC).plusDays(2)
            )
    }

    @AfterEach
    fun cleanUp() {
        outboxEventRepository.deleteAll()
        reservationSeatRepository.deleteAll()
        reservationRepository.deleteAll()
    }

    @Test
    @DisplayName("PENDING 예매 취소 시 outbox_events row 1건이 INSERT 되고 id == payload eventId 이다 (SOT)")
    fun cancelInsertsOutboxRowWithIdEqualToEventId() {
        val reservation = persistPendingReservation()
        persistSeats(reservation.id!!, listOf(seatIdA, seatIdB))

        mockMvc.perform(
            delete("/reservations/{reservationId}", reservation.id)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER")
        ).andExpect(status().isOk)

        val outboxRows = outboxEventRepository.findAll().toList()
        outboxRows shouldHaveSize 1

        val row = outboxRows.single()
        row.aggregateType shouldBe "Reservation"
        row.aggregateId shouldBe reservation.id
        row.eventType shouldBe "ReservationCancelled"
        row.published shouldBe false

        val payload = objectMapper.readValue<ReservationCancelledEvent>(row.payload)
        row.id shouldBe payload.eventId
        payload.scheduleId shouldBe scheduleId
        payload.userId shouldBe userId
        payload.seatIds.sortedBy { it.toString() } shouldBe
            listOf(seatIdA, seatIdB).sortedBy { it.toString() }
        payload.reason shouldBe "USER_REQUEST"
    }

    private fun persistPendingReservation(): Reservation {
        val reservation = Reservation(
            userId = userId,
            scheduleId = scheduleId,
            eventId = eventId,
            status = ReservationStatus.PENDING,
            totalAmount = BigDecimal("200000"),
            holdExpiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5)
        )
        return reservationRepository.save(reservation)
    }

    private fun persistSeats(reservationId: UUID, seatIds: List<UUID>) {
        seatIds.forEachIndexed { idx, seatId ->
            reservationSeatRepository.save(
                ReservationSeat(
                    reservationId = reservationId,
                    seatId = seatId,
                    seatNumber = "A-${idx + 1}",
                    grade = "VIP",
                    price = BigDecimal("100000")
                )
            )
        }
    }
}
