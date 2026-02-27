package com.ticketqueue.event.service

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.event.dto.SeatDto
import com.ticketqueue.event.entity.EventSchedule
import com.ticketqueue.event.entity.Seat
import com.ticketqueue.event.entity.SeatGrade
import com.ticketqueue.event.entity.SeatStatus
import com.ticketqueue.event.exception.EventException
import com.ticketqueue.event.repository.EventScheduleRepository
import com.ticketqueue.event.repository.SeatRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID

class SeatServiceTest {

    private lateinit var eventScheduleRepository: EventScheduleRepository
    private lateinit var seatRepository: SeatRepository
    private lateinit var redisTemplate: RedisTemplate<String, Any>
    private lateinit var valueOps: ValueOperations<String, Any>
    private lateinit var seatService: SeatService

    private val objectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
    }

    private val scheduleId = UUID.randomUUID()

    private fun createSeat(
        grade: SeatGrade,
        seatNumber: String,
        price: BigDecimal,
        status: SeatStatus = SeatStatus.AVAILABLE
    ): Seat {
        val schedule = mockk<EventSchedule>()
        return Seat(
            id = UUID.randomUUID(),
            eventSchedule = schedule,
            seatNumber = seatNumber,
            grade = grade,
            price = price,
            status = status
        )
    }

    @BeforeEach
    fun setUp() {
        eventScheduleRepository = mockk()
        seatRepository = mockk()
        redisTemplate = mockk()
        valueOps = mockk()
        every { redisTemplate.opsForValue() } returns valueOps
        seatService = SeatService(
            eventScheduleRepository = eventScheduleRepository,
            seatRepository = seatRepository,
            redisTemplate = redisTemplate,
            objectMapper = objectMapper,
            seatsCacheTtl = 300L
        )
    }

    @Nested
    @DisplayName("getSeats")
    inner class GetSeats {

        @Test
        @DisplayName("캐시 Miss - DB 조회 후 등급별 그룹핑하여 반환하고 캐시에 저장한다")
        fun cacheMissAndFetchFromDb() {
            val seats = listOf(
                createSeat(SeatGrade.VIP, "A-1", BigDecimal("150000")),
                createSeat(SeatGrade.S, "B-1", BigDecimal("100000"))
            )
            every { valueOps.get(any<String>()) } returns null
            every { eventScheduleRepository.existsById(scheduleId) } returns true
            every { seatRepository.findByScheduleIdOrderByGradeAndSeatNumber(scheduleId) } returns seats
            every { valueOps.set(any<String>(), any(), any<Duration>()) } just runs

            val response = seatService.getSeats(scheduleId)

            assertEquals(scheduleId, response.scheduleId)
            assertEquals(2, response.grades.size)
            assertEquals(SeatGrade.VIP, response.grades[0].grade)
            assertEquals(SeatGrade.S, response.grades[1].grade)
            verify { valueOps.set("cache:seats:$scheduleId", any(), Duration.ofSeconds(300)) }
        }

        @Test
        @DisplayName("캐시 Hit - DB 조회 없이 캐시에서 즉시 반환한다")
        fun cacheHit() {
            val cachedResponse = SeatDto.SeatsResponse(
                scheduleId = scheduleId,
                grades = emptyList()
            )
            every { valueOps.get("cache:seats:$scheduleId") } returns cachedResponse

            val response = seatService.getSeats(scheduleId)

            assertEquals(scheduleId, response.scheduleId)
            verify(exactly = 0) { seatRepository.findByScheduleIdOrderByGradeAndSeatNumber(any()) }
        }

        @Test
        @DisplayName("Redis 장애 시 DB fallback으로 정상 응답한다")
        fun redisFallback() {
            val seats = listOf(createSeat(SeatGrade.VIP, "A-1", BigDecimal("150000")))
            every { valueOps.get(any<String>()) } throws RedisConnectionFailureException("connection failed")
            every { eventScheduleRepository.existsById(scheduleId) } returns true
            every { seatRepository.findByScheduleIdOrderByGradeAndSeatNumber(scheduleId) } returns seats
            every { valueOps.set(any<String>(), any(), any<Duration>()) } just runs

            val response = seatService.getSeats(scheduleId)

            assertEquals(scheduleId, response.scheduleId)
            assertEquals(1, response.grades.size)
        }

        @Test
        @DisplayName("등급 정렬 순서는 VIP → S → A → B 이다")
        fun gradeOrder() {
            val seats = listOf(
                createSeat(SeatGrade.B, "D-1", BigDecimal("50000")),
                createSeat(SeatGrade.A, "C-1", BigDecimal("70000")),
                createSeat(SeatGrade.S, "B-1", BigDecimal("100000")),
                createSeat(SeatGrade.VIP, "A-1", BigDecimal("150000"))
            )
            every { valueOps.get(any<String>()) } returns null
            every { eventScheduleRepository.existsById(scheduleId) } returns true
            every { seatRepository.findByScheduleIdOrderByGradeAndSeatNumber(scheduleId) } returns seats
            every { valueOps.set(any<String>(), any(), any<Duration>()) } just runs

            val response = seatService.getSeats(scheduleId)

            val gradeOrder = response.grades.map { it.grade }
            assertEquals(listOf(SeatGrade.VIP, SeatGrade.S, SeatGrade.A, SeatGrade.B), gradeOrder)
        }

        @Test
        @DisplayName("좌석 없는 회차 - 빈 grades 반환")
        fun emptySeats() {
            every { valueOps.get(any<String>()) } returns null
            every { eventScheduleRepository.existsById(scheduleId) } returns true
            every { seatRepository.findByScheduleIdOrderByGradeAndSeatNumber(scheduleId) } returns emptyList()
            every { valueOps.set(any<String>(), any(), any<Duration>()) } just runs

            val response = seatService.getSeats(scheduleId)

            assertEquals(scheduleId, response.scheduleId)
            assertTrue(response.grades.isEmpty())
        }

        @Test
        @DisplayName("존재하지 않는 scheduleId - SCHEDULE_NOT_FOUND 예외 발생")
        fun scheduleNotFound() {
            every { valueOps.get(any<String>()) } returns null
            every { eventScheduleRepository.existsById(scheduleId) } returns false

            val exception = assertThrows<EventException> {
                seatService.getSeats(scheduleId)
            }

            assertEquals(ErrorCode.SCHEDULE_NOT_FOUND, exception.errorCode)
        }

        @Test
        @DisplayName("캐시 저장 실패 시에도 정상 응답한다")
        fun cacheWriteFailure() {
            val seats = listOf(createSeat(SeatGrade.VIP, "A-1", BigDecimal("150000")))
            every { valueOps.get(any<String>()) } returns null
            every { eventScheduleRepository.existsById(scheduleId) } returns true
            every { seatRepository.findByScheduleIdOrderByGradeAndSeatNumber(scheduleId) } returns seats
            every { valueOps.set(any<String>(), any(), any<Duration>()) } throws RuntimeException("Redis write failed")

            val response = seatService.getSeats(scheduleId)

            assertEquals(scheduleId, response.scheduleId)
            assertEquals(1, response.grades.size)
        }
    }

    @Nested
    @DisplayName("getSoldSeatIds")
    inner class GetSoldSeatIds {

        @Test
        @DisplayName("SOLD 좌석 ID 목록을 반환한다")
        fun returnsSoldSeatIds() {
            val soldIds = listOf(UUID.randomUUID(), UUID.randomUUID())
            every { eventScheduleRepository.existsById(scheduleId) } returns true
            every { seatRepository.findSoldSeatIdsByScheduleId(scheduleId) } returns soldIds

            val response = seatService.getSoldSeatIds(scheduleId)

            assertEquals(scheduleId, response.scheduleId)
            assertEquals(soldIds, response.soldSeatIds)
        }

        @Test
        @DisplayName("SOLD 좌석 없으면 빈 리스트를 반환한다")
        fun returnsEmptyListWhenNoSoldSeats() {
            every { eventScheduleRepository.existsById(scheduleId) } returns true
            every { seatRepository.findSoldSeatIdsByScheduleId(scheduleId) } returns emptyList()

            val response = seatService.getSoldSeatIds(scheduleId)

            assertEquals(scheduleId, response.scheduleId)
            assertTrue(response.soldSeatIds.isEmpty())
        }

        @Test
        @DisplayName("존재하지 않는 scheduleId - SCHEDULE_NOT_FOUND 예외 발생")
        fun scheduleNotFound() {
            every { eventScheduleRepository.existsById(scheduleId) } returns false

            val exception = assertThrows<EventException> {
                seatService.getSoldSeatIds(scheduleId)
            }

            assertEquals(ErrorCode.SCHEDULE_NOT_FOUND, exception.errorCode)
        }
    }
}
