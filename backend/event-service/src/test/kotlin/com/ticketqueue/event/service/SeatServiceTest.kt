package com.ticketqueue.event.service

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.event.config.CacheProperties
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
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.SetOperations
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID

class SeatServiceTest {

    private lateinit var eventScheduleRepository: EventScheduleRepository
    private lateinit var seatRepository: SeatRepository
    private lateinit var redisTemplate: RedisTemplate<String, Any>
    private lateinit var hashOps: HashOperations<String, String, Any>
    private lateinit var setOps: SetOperations<String, Any>
    private lateinit var cacheHelper: CacheHelper
    private lateinit var seatService: SeatService

    private val objectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
    }
    private val cacheProperties = CacheProperties()

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
        hashOps = mockk()
        setOps = mockk()
        cacheHelper = mockk()
        every { redisTemplate.opsForHash<String, Any>() } returns hashOps
        every { redisTemplate.opsForSet() } returns setOps
        // Stampede Lock: 기본적으로 락 획득 성공 (DB 조회 + 캐시 저장 허용)
        every { cacheHelper.tryAcquireStampedeLock(any(), any()) } returns true
        seatService = SeatService(
            eventScheduleRepository = eventScheduleRepository,
            seatRepository = seatRepository,
            redisTemplate = redisTemplate,
            objectMapper = objectMapper,
            cacheProperties = cacheProperties,
            cacheHelper = cacheHelper
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
            every { hashOps.entries(any<String>()) } returns emptyMap()
            every { eventScheduleRepository.existsById(scheduleId) } returns true
            every { seatRepository.findByScheduleIdOrderByGradeAndSeatNumber(scheduleId) } returns seats
            every { hashOps.putAll(any<String>(), any()) } just runs
            every { redisTemplate.expire(any<String>(), any<Duration>()) } returns true
            every { setOps.members(any<String>()) } returns emptySet()

            val response = seatService.getSeats(scheduleId)

            assertEquals(scheduleId, response.scheduleId)
            assertEquals(2, response.grades.size)
            assertEquals(SeatGrade.VIP, response.grades[0].grade)
            assertEquals(SeatGrade.S, response.grades[1].grade)
            verify { hashOps.putAll("cache:seats:$scheduleId", any()) }
            verify { redisTemplate.expire("cache:seats:$scheduleId", Duration.ofSeconds(300)) }
        }

        @Test
        @DisplayName("캐시 Hit - DB 조회 없이 캐시에서 즉시 반환한다")
        fun cacheHit() {
            val gradeGroup = SeatDto.GradeGroup(
                grade = SeatGrade.VIP,
                price = BigDecimal("150000"),
                seats = emptyList()
            )
            every { hashOps.entries("cache:seats:$scheduleId") } returns mapOf("VIP" to gradeGroup)
            every { setOps.members(any<String>()) } returns emptySet()

            val response = seatService.getSeats(scheduleId)

            assertEquals(scheduleId, response.scheduleId)
            verify(exactly = 0) { seatRepository.findByScheduleIdOrderByGradeAndSeatNumber(any()) }
        }

        @Test
        @DisplayName("Redis 장애 시 DB fallback으로 정상 응답한다")
        fun redisFallback() {
            val seats = listOf(createSeat(SeatGrade.VIP, "A-1", BigDecimal("150000")))
            every { hashOps.entries(any<String>()) } throws RedisConnectionFailureException("connection failed")
            every { eventScheduleRepository.existsById(scheduleId) } returns true
            every { seatRepository.findByScheduleIdOrderByGradeAndSeatNumber(scheduleId) } returns seats
            every { hashOps.putAll(any<String>(), any()) } just runs
            every { redisTemplate.expire(any<String>(), any<Duration>()) } returns true
            every { setOps.members(any<String>()) } returns emptySet()

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
            every { hashOps.entries(any<String>()) } returns emptyMap()
            every { eventScheduleRepository.existsById(scheduleId) } returns true
            every { seatRepository.findByScheduleIdOrderByGradeAndSeatNumber(scheduleId) } returns seats
            every { hashOps.putAll(any<String>(), any()) } just runs
            every { redisTemplate.expire(any<String>(), any<Duration>()) } returns true
            every { setOps.members(any<String>()) } returns emptySet()

            val response = seatService.getSeats(scheduleId)

            val gradeOrder = response.grades.map { it.grade }
            assertEquals(listOf(SeatGrade.VIP, SeatGrade.S, SeatGrade.A, SeatGrade.B), gradeOrder)
        }

        @Test
        @DisplayName("좌석 없는 회차 - 빈 grades 반환")
        fun emptySeats() {
            every { hashOps.entries(any<String>()) } returns emptyMap()
            every { eventScheduleRepository.existsById(scheduleId) } returns true
            every { seatRepository.findByScheduleIdOrderByGradeAndSeatNumber(scheduleId) } returns emptyList()
            every { hashOps.putAll(any<String>(), any()) } just runs
            every { redisTemplate.expire(any<String>(), any<Duration>()) } returns true
            every { setOps.members(any<String>()) } returns emptySet()

            val response = seatService.getSeats(scheduleId)

            assertEquals(scheduleId, response.scheduleId)
            assertTrue(response.grades.isEmpty())
        }

        @Test
        @DisplayName("존재하지 않는 scheduleId - SCHEDULE_NOT_FOUND 예외 발생")
        fun scheduleNotFound() {
            every { hashOps.entries(any<String>()) } returns emptyMap()
            every { eventScheduleRepository.existsById(scheduleId) } returns false

            val exception = assertThrows<EventException> {
                seatService.getSeats(scheduleId)
            }

            assertEquals(ErrorCode.SCHEDULE_NOT_FOUND, exception.errorCode)
        }

        @Test
        @DisplayName("HOLD 오버레이 - AVAILABLE 좌석이 hold_seats SET에 있으면 HOLD로 변환한다")
        fun holdOverlay() {
            val seats = listOf(createSeat(SeatGrade.VIP, "A-1", BigDecimal("150000")))
            val seatId = seats[0].id!!
            every { hashOps.entries(any<String>()) } returns emptyMap()
            every { eventScheduleRepository.existsById(scheduleId) } returns true
            every { seatRepository.findByScheduleIdOrderByGradeAndSeatNumber(scheduleId) } returns seats
            every { hashOps.putAll(any<String>(), any()) } just runs
            every { redisTemplate.expire(any<String>(), any<Duration>()) } returns true
            every { setOps.members("hold_seats:$scheduleId") } returns setOf(seatId.toString())

            val response = seatService.getSeats(scheduleId)

            assertEquals(SeatStatus.HOLD, response.grades[0].seats[0].status)
        }

        @Test
        @DisplayName("HOLD 오버레이 - SOLD 좌석은 hold_seats SET에 있어도 SOLD 유지한다")
        fun holdOverlayDoesNotAffectSoldSeats() {
            val seats = listOf(createSeat(SeatGrade.VIP, "A-1", BigDecimal("150000"), SeatStatus.SOLD))
            val seatId = seats[0].id!!
            every { hashOps.entries(any<String>()) } returns emptyMap()
            every { eventScheduleRepository.existsById(scheduleId) } returns true
            every { seatRepository.findByScheduleIdOrderByGradeAndSeatNumber(scheduleId) } returns seats
            every { hashOps.putAll(any<String>(), any()) } just runs
            every { redisTemplate.expire(any<String>(), any<Duration>()) } returns true
            every { setOps.members("hold_seats:$scheduleId") } returns setOf(seatId.toString())

            val response = seatService.getSeats(scheduleId)

            assertEquals(SeatStatus.SOLD, response.grades[0].seats[0].status)
        }

        @Test
        @DisplayName("HOLD 오버레이 - Redis SET 조회 장애 시 오버레이 없이 정상 응답한다")
        fun holdOverlayRedisFailureFallback() {
            val seats = listOf(createSeat(SeatGrade.VIP, "A-1", BigDecimal("150000")))
            every { hashOps.entries(any<String>()) } returns emptyMap()
            every { eventScheduleRepository.existsById(scheduleId) } returns true
            every { seatRepository.findByScheduleIdOrderByGradeAndSeatNumber(scheduleId) } returns seats
            every { hashOps.putAll(any<String>(), any()) } just runs
            every { redisTemplate.expire(any<String>(), any<Duration>()) } returns true
            every { setOps.members(any<String>()) } throws RedisConnectionFailureException("connection failed")

            val response = seatService.getSeats(scheduleId)

            assertEquals(SeatStatus.AVAILABLE, response.grades[0].seats[0].status)
        }

        @Test
        @DisplayName("HOLD 오버레이 - hold_seats SET이 비어있으면 오버레이 없이 반환한다")
        fun holdOverlayEmptySet() {
            val seats = listOf(createSeat(SeatGrade.VIP, "A-1", BigDecimal("150000")))
            every { hashOps.entries(any<String>()) } returns emptyMap()
            every { eventScheduleRepository.existsById(scheduleId) } returns true
            every { seatRepository.findByScheduleIdOrderByGradeAndSeatNumber(scheduleId) } returns seats
            every { hashOps.putAll(any<String>(), any()) } just runs
            every { redisTemplate.expire(any<String>(), any<Duration>()) } returns true
            every { setOps.members("hold_seats:$scheduleId") } returns emptySet()

            val response = seatService.getSeats(scheduleId)

            assertEquals(SeatStatus.AVAILABLE, response.grades[0].seats[0].status)
        }

        @Test
        @DisplayName("캐시 저장 실패 시에도 정상 응답한다")
        fun cacheWriteFailure() {
            val seats = listOf(createSeat(SeatGrade.VIP, "A-1", BigDecimal("150000")))
            every { hashOps.entries(any<String>()) } returns emptyMap()
            every { eventScheduleRepository.existsById(scheduleId) } returns true
            every { seatRepository.findByScheduleIdOrderByGradeAndSeatNumber(scheduleId) } returns seats
            every { hashOps.putAll(any<String>(), any()) } throws RedisConnectionFailureException("Redis write failed")
            every { setOps.members(any<String>()) } returns emptySet()

            val response = seatService.getSeats(scheduleId)

            assertEquals(scheduleId, response.scheduleId)
            assertEquals(1, response.grades.size)
        }

        @Test
        @DisplayName("Stampede Lock 미획득 시 캐시 저장을 건너뛰고 정상 응답한다")
        fun stampedeLockNotAcquired() {
            val seats = listOf(createSeat(SeatGrade.VIP, "A-1", BigDecimal("150000")))
            every { hashOps.entries(any<String>()) } returns emptyMap()
            every { eventScheduleRepository.existsById(scheduleId) } returns true
            every { seatRepository.findByScheduleIdOrderByGradeAndSeatNumber(scheduleId) } returns seats
            // 락 미획득 (다른 요청이 이미 처리 중)
            every { cacheHelper.tryAcquireStampedeLock(any(), any()) } returns false
            every { setOps.members(any<String>()) } returns emptySet()

            val response = seatService.getSeats(scheduleId)

            assertEquals(scheduleId, response.scheduleId)
            assertEquals(1, response.grades.size)
            // 락 미획득 시 캐시 저장 미수행
            verify(exactly = 0) { hashOps.putAll(any(), any()) }
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
            every { seatRepository.countByEventScheduleId(scheduleId) } returns (soldIds.size + 3).toLong()

            val response = seatService.getSoldSeatIds(scheduleId)

            assertEquals(scheduleId, response.scheduleId)
            assertEquals(soldIds, response.soldSeatIds)
            assertEquals((soldIds.size + 3).toLong(), response.totalSeats)
        }

        @Test
        @DisplayName("SOLD 좌석 없으면 빈 리스트를 반환한다")
        fun returnsEmptyListWhenNoSoldSeats() {
            every { eventScheduleRepository.existsById(scheduleId) } returns true
            every { seatRepository.findSoldSeatIdsByScheduleId(scheduleId) } returns emptyList()
            every { seatRepository.countByEventScheduleId(scheduleId) } returns 5L

            val response = seatService.getSoldSeatIds(scheduleId)

            assertEquals(scheduleId, response.scheduleId)
            assertTrue(response.soldSeatIds.isEmpty())
            assertEquals(5L, response.totalSeats)
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

    @Nested
    @DisplayName("markSeatsAsSold")
    inner class MarkSeatsAsSold {

        private val seatIds = listOf(UUID.randomUUID(), UUID.randomUUID())

        @Test
        @DisplayName("DB를 bulk update하고 캐시를 무효화한다")
        fun updatesDbAndEvictsCache() {
            every { seatRepository.updateStatusToSold(scheduleId, seatIds) } returns 2L
            every { redisTemplate.delete(any<String>()) } returns true

            seatService.markSeatsAsSold(scheduleId, seatIds)

            verify { seatRepository.updateStatusToSold(scheduleId, seatIds) }
            verify { redisTemplate.delete("cache:seats:$scheduleId") }
        }

        @Test
        @DisplayName("Redis 장애 시 캐시 삭제 실패해도 예외를 전파하지 않는다")
        fun doesNotPropagateRedisFailureOnCacheEviction() {
            every { seatRepository.updateStatusToSold(scheduleId, seatIds) } returns 2L
            every { redisTemplate.delete(any<String>()) } throws RedisConnectionFailureException("connection failed")

            seatService.markSeatsAsSold(scheduleId, seatIds)

            verify { seatRepository.updateStatusToSold(scheduleId, seatIds) }
        }
    }

    @Nested
    @DisplayName("releaseHoldSeats")
    inner class ReleaseHoldSeats {

        private val seatIds = listOf(UUID.randomUUID(), UUID.randomUUID())

        @Test
        @DisplayName("DB를 AVAILABLE로 복원하고 Redis hold_seats에서 좌석을 제거하며 캐시를 무효화한다")
        fun removesFromHoldSeatsAndEvictsCache() {
            every { seatRepository.updateStatusToAvailable(scheduleId, seatIds) } returns 2L
            every { setOps.remove(any<String>(), *anyVararg()) } returns 2L
            every { redisTemplate.delete(any<String>()) } returns true

            seatService.releaseHoldSeats(scheduleId, seatIds)

            verify { seatRepository.updateStatusToAvailable(scheduleId, seatIds) }
            verify { setOps.remove("hold_seats:$scheduleId", *anyVararg()) }
            verify { redisTemplate.delete("cache:seats:$scheduleId") }
        }

        @Test
        @DisplayName("Redis 장애 시 예외를 전파하지 않는다")
        fun doesNotPropagateRedisFailure() {
            every { seatRepository.updateStatusToAvailable(scheduleId, seatIds) } returns 2L
            every { setOps.remove(any<String>(), *anyVararg()) } throws RedisConnectionFailureException("connection failed")
            every { redisTemplate.delete(any<String>()) } returns true

            seatService.releaseHoldSeats(scheduleId, seatIds)
        }
    }
}
