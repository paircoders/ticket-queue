package com.ticketqueue.event.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.event.config.CacheProperties
import com.ticketqueue.event.dto.SeatDto
import com.ticketqueue.event.entity.SeatStatus
import com.ticketqueue.event.exception.EventException
import com.ticketqueue.event.repository.EventScheduleRepository
import com.ticketqueue.event.repository.SeatRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

/**
 * 좌석(Seat) 서비스
 *
 * EventService와 분리하여 SRP를 준수한다.
 * - [getSeats]: 회차별 좌석 등급 그룹핑 조회 + Redis Cache-Aside (TTL 5분) - REQ-EVT-006
 * - [getSoldSeatIds]: SOLD 좌석 ID 조회 (캐싱 없음 - 실시간 정확도 우선)
 * - [markSeatsAsSold]: Kafka Consumer용 - 좌석 상태 SOLD 벌크 업데이트
 * - [releaseHoldSeats]: Kafka Consumer용 - DB AVAILABLE 복원 + Redis hold_seats 선점 해제
 *
 * Redis 장애 시 try-catch로 무시하고 DB fallback을 수행하여 서비스 가용성을 유지한다.
 * Cache Stampede 방지: Lua 스크립트로 원자적 락 획득 (REQ-EVT-021)
 */
@Service
@Transactional(readOnly = true)
class SeatService(
    private val eventScheduleRepository: EventScheduleRepository,
    private val seatRepository: SeatRepository,
    private val redisTemplate: RedisTemplate<String, Any>,
    private val objectMapper: ObjectMapper,
    private val cacheProperties: CacheProperties,
    private val cacheHelper: CacheHelper
) {

    private val log = LoggerFactory.getLogger(SeatService::class.java)
    private val cacheKeyPrefix = "cache:seats:"
    private val lockKeyPrefix = "cache:lock:seats:"
    private val stampedeLockTtl = 10L

    /**
     * 회차별 좌석 목록을 등급 그룹핑하여 반환한다 (REQ-EVT-006, P95 < 300ms)
     *
     * Cache-Aside 전략:
     * 1. Redis Hash 캐시 조회 (등급별 필드) → Hit 시 즉시 반환
     * 2. Miss 시 → Stampede Lock 시도 → DB 조회 → 등급별 그룹핑 → Hash 필드로 캐시 저장
     *
     * Redis 장애 시 DB fallback으로 정상 응답을 보장한다.
     */
    fun getSeats(scheduleId: UUID): SeatDto.SeatsResponse {
        val cacheKey = "$cacheKeyPrefix$scheduleId"

        try {
            val hashEntries = redisTemplate.opsForHash<String, Any>().entries(cacheKey)
            if (hashEntries.isNotEmpty()) {
                val grades = hashEntries.values
                    .map { objectMapper.convertValue(it, SeatDto.GradeGroup::class.java) }
                    .sortedBy { it.grade.ordinal }
                val cachedResponse = SeatDto.SeatsResponse(scheduleId = scheduleId, grades = grades)
                val holdSeatIds = getHoldSeatIds(scheduleId)
                return overlayHoldStatus(cachedResponse, holdSeatIds)
            }
        } catch (e: DataAccessException) {
            log.warn("Redis cache read failed for key: $cacheKey", e)
        } catch (e: IllegalArgumentException) {
            log.warn("Redis cache deserialization failed for key: $cacheKey, deleting corrupted cache", e)
            redisTemplate.delete(cacheKey)
        }

        if (!eventScheduleRepository.existsById(scheduleId)) {
            throw EventException(ErrorCode.SCHEDULE_NOT_FOUND)
        }

        val seats = seatRepository.findByScheduleIdOrderByGradeAndSeatNumber(scheduleId)

        val grades = seats
            .groupBy { it.grade }
            .entries
            .sortedBy { (grade, _) -> grade.ordinal }
            .map { (grade, gradeSeats) ->
                SeatDto.GradeGroup(
                    grade = grade,
                    price = gradeSeats.first().price,
                    seats = gradeSeats.map { SeatDto.SeatInfo.from(it) }
                )
            }

        val response = SeatDto.SeatsResponse(scheduleId = scheduleId, grades = grades)

        // Stampede Lock 획득 성공 시에만 캐시 저장
        val lockKey = "$lockKeyPrefix$scheduleId"
        val lockAcquired = cacheHelper.tryAcquireStampedeLock(lockKey, stampedeLockTtl)

        if (lockAcquired) {
            try {
                val gradeMap = response.grades.associate { gradeGroup -> gradeGroup.grade.name to gradeGroup }
                redisTemplate.opsForHash<String, Any>().putAll(cacheKey, gradeMap)
                redisTemplate.expire(cacheKey, Duration.ofSeconds(cacheProperties.seats.ttl))
            } catch (e: DataAccessException) {
                log.warn("Redis cache write failed for key: $cacheKey", e)
            }
        }

        val holdSeatIds = getHoldSeatIds(scheduleId)
        return overlayHoldStatus(response, holdSeatIds)
    }

    /**
     * 회차의 SOLD 좌석 ID 목록을 반환한다 (내부 API - Reservation Service 전용)
     *
     * 실시간 정확도 우선으로 캐싱을 적용하지 않는다.
     */
    fun getSoldSeatIds(scheduleId: UUID): SeatDto.SoldSeatsResponse {
        if (!eventScheduleRepository.existsById(scheduleId)) {
            throw EventException(ErrorCode.SCHEDULE_NOT_FOUND)
        }

        val soldSeatIds = seatRepository.findSoldSeatIdsByScheduleId(scheduleId)
        return SeatDto.SoldSeatsResponse(scheduleId = scheduleId, soldSeatIds = soldSeatIds)
    }

    /**
     * 좌석 상태를 SOLD로 벌크 업데이트한다 (Kafka Consumer - PaymentSuccess 처리)
     *
     * QueryDSL 벌크 UPDATE로 처리하며, seat.status.ne(SOLD) 조건으로 멱등성을 보장한다.
     * 업데이트 후 캐시를 무효화하여 다음 조회 시 최신 상태를 반영한다.
     */
    @Transactional
    fun markSeatsAsSold(scheduleId: UUID, seatIds: List<UUID>) {
        val updatedCount = seatRepository.updateStatusToSold(scheduleId, seatIds)
        log.info("Marked $updatedCount seats as SOLD: scheduleId=$scheduleId, requested=${seatIds.size}")
        evictSeatsCache(scheduleId)
    }

    /**
     * 좌석 선점 시 스냅샷 저장을 위한 상세 정보 조회 (내부 API - Reservation Service 전용)
     *
     * seatIds 개수와 조회된 좌석 수가 불일치하면 RESOURCE_NOT_FOUND 예외를 던진다.
     * AVAILABLE 상태가 아닌 좌석(HOLD/SOLD)이 포함되면 SEAT_NOT_AVAILABLE 예외를 던진다.
     * eventId는 EventSchedule 로드 시 Hibernate 프록시의 FK 값을 직접 참조하여 추가 쿼리 없이 추출한다.
     */
    fun getSeatDetails(scheduleId: UUID, seatIds: List<UUID>): SeatDto.SeatDetailsResponse {
        val schedule = eventScheduleRepository.findById(scheduleId)
            .orElseThrow { EventException(ErrorCode.SCHEDULE_NOT_FOUND) }

        if (seatIds.isEmpty()) throw EventException(ErrorCode.INVALID_INPUT)
        val distinctIds = seatIds.toSet()
        if (distinctIds.size != seatIds.size) {
            throw EventException(ErrorCode.INVALID_INPUT)
        }

        val seats = seatRepository.findByEventScheduleIdAndIdIn(scheduleId, distinctIds.toList())
        if (seats.size != distinctIds.size) {
            throw EventException(ErrorCode.RESOURCE_NOT_FOUND)
        }

        seats.find { it.status != SeatStatus.AVAILABLE }?.let {
            throw EventException(ErrorCode.SEAT_NOT_AVAILABLE)
        }

        val eventId = schedule.event.id ?: throw EventException(ErrorCode.INTERNAL_SERVER_ERROR)
        return SeatDto.SeatDetailsResponse(
            scheduleId = scheduleId,
            eventId = eventId,
            seats = seats.map { seat ->
                SeatDto.SeatDetailsResponse.SeatDetail(
                    seatId = seat.id!!,
                    seatNumber = seat.seatNumber,
                    grade = seat.grade.name,
                    price = seat.price
                )
            }
        )
    }

    /**
     * 좌석을 AVAILABLE로 복원하고 Redis hold_seats Set에서 선점 해제된 좌석을 제거한다 (Kafka Consumer - ReservationCancelled 처리)
     *
     * DB AVAILABLE 복원이 트랜잭션 내에서 우선 보장되고, Redis는 best-effort로 정리한다.
     * TTL 10분 자동 만료로 Redis 장애 시에도 안전하다.
     * 캐시도 무효화하여 다음 조회 시 최신 상태를 반영한다.
     */
    @Transactional
    fun releaseHoldSeats(scheduleId: UUID, seatIds: List<UUID>) {
        val updatedCount = seatRepository.updateStatusToAvailable(scheduleId, seatIds)
        log.info("좌석 AVAILABLE 복원: scheduleId=$scheduleId, updated=$updatedCount/${seatIds.size}")
        removeFromHoldSeatsRedis(scheduleId, seatIds)
        evictSeatsCache(scheduleId)
    }

    private fun evictSeatsCache(scheduleId: UUID) {
        val cacheKey = "$cacheKeyPrefix$scheduleId"
        try {
            redisTemplate.delete(cacheKey)
            log.debug("Evicted seats cache: key=$cacheKey")
        } catch (e: DataAccessException) {
            log.warn("Failed to evict seats cache: key=$cacheKey", e)
        }
    }

    /**
     * Redis hold_seats:{scheduleId} SET에서 선점 중인 좌석 ID 목록을 조회한다.
     *
     * Redis 장애 시 emptySet()을 반환하여 오버레이 없이 정상 응답을 보장한다.
     */
    private fun getHoldSeatIds(scheduleId: UUID): Set<UUID> {
        val holdKey = "hold_seats:$scheduleId"
        return try {
            redisTemplate.opsForSet().members(holdKey)
                ?.mapNotNull { runCatching { UUID.fromString(it.toString()) }.getOrNull() }
                ?.toSet() ?: emptySet()
        } catch (e: DataAccessException) {
            log.warn("Failed to read hold seats from Redis: key=$holdKey", e)
            emptySet()
        }
    }

    /**
     * AVAILABLE 좌석 중 hold SET에 포함된 좌석의 상태를 HOLD로 오버레이한다.
     *
     * SOLD 좌석은 DB 상태를 우선하여 오버레이하지 않는다.
     * 캐시 저장은 오버레이 전(AVAILABLE/SOLD 상태)으로 수행된다.
     */
    private fun overlayHoldStatus(response: SeatDto.SeatsResponse, holdSeatIds: Set<UUID>): SeatDto.SeatsResponse {
        if (holdSeatIds.isEmpty()) return response
        val overlaidGrades = response.grades.map { gradeGroup ->
            gradeGroup.copy(seats = gradeGroup.seats.map { seat ->
                if (seat.status == SeatStatus.AVAILABLE && seat.id in holdSeatIds)
                    seat.copy(status = SeatStatus.HOLD)
                else seat
            })
        }
        return response.copy(grades = overlaidGrades)
    }

    private fun removeFromHoldSeatsRedis(scheduleId: UUID, seatIds: List<UUID>) {
        val holdKey = "hold_seats:$scheduleId"
        try {
            val members = seatIds.map { it.toString() }.toTypedArray<Any>()
            redisTemplate.opsForSet().remove(holdKey, *members)
            log.debug("Removed ${seatIds.size} seats from hold set: key=$holdKey")
        } catch (e: DataAccessException) {
            log.warn("Failed to remove seats from hold set: key=$holdKey. TTL will expire automatically.", e)
        }
    }
}
