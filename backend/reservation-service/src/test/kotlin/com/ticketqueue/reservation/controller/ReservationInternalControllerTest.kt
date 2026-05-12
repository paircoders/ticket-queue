package com.ticketqueue.reservation.controller

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.reservation.entity.Reservation
import com.ticketqueue.reservation.entity.ReservationSeat
import com.ticketqueue.reservation.entity.ReservationStatus
import com.ticketqueue.reservation.exception.ReservationException
import com.ticketqueue.reservation.repository.ReservationRepository
import com.ticketqueue.reservation.repository.ReservationSeatRepository
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

@DisplayName("ReservationInternalController 단위 테스트")
class ReservationInternalControllerTest {

    private lateinit var reservationRepository: ReservationRepository
    private lateinit var reservationSeatRepository: ReservationSeatRepository
    private lateinit var controller: ReservationInternalController
    private lateinit var mockMvc: MockMvc

    private val reservationId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val scheduleId = UUID.randomUUID()
    private val seatId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        reservationRepository = mockk()
        reservationSeatRepository = mockk()
        controller = ReservationInternalController(reservationRepository, reservationSeatRepository)
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build()
    }

    private fun buildReservation() = Reservation(
        id = reservationId,
        userId = userId,
        scheduleId = scheduleId,
        eventId = UUID.randomUUID(),
        totalAmount = BigDecimal("300000"),
        holdExpiresAt = LocalDateTime.now().plusMinutes(5)
    )

    private fun buildSeat() = ReservationSeat(
        reservationId = reservationId,
        seatId = seatId,
        seatNumber = "A-01",
        grade = "VIP",
        price = BigDecimal("300000")
    )

    @Nested
    @DisplayName("GET /internal/reservations/{reservationId}")
    inner class GetReservation {

        @Test
        @DisplayName("예매 ID로 예매 상세 정보와 좌석 ID 목록을 반환한다")
        fun success() {
            every { reservationRepository.findById(reservationId) } returns Optional.of(buildReservation())
            every { reservationSeatRepository.findByReservationId(reservationId) } returns listOf(buildSeat())

            mockMvc.perform(get("/internal/reservations/$reservationId"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.reservationId").value(reservationId.toString()))
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.scheduleId").value(scheduleId.toString()))
                .andExpect(jsonPath("$.totalAmount").value(300000))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.seatIds[0]").value(seatId.toString()))
        }

        @Test
        @DisplayName("존재하지 않는 예매 ID 조회 시 ReservationException이 발생한다")
        fun notFound() {
            every { reservationRepository.findById(reservationId) } returns Optional.empty()

            val ex = assertThrows<ReservationException> {
                controller.getReservation(reservationId)
            }

            ex.errorCode shouldBe ErrorCode.RESERVATION_NOT_FOUND
        }
    }
}
