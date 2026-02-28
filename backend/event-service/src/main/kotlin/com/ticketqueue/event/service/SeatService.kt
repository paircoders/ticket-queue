package com.ticketqueue.event.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.event.dto.SeatDto
import com.ticketqueue.event.exception.EventException
import com.ticketqueue.event.repository.EventScheduleRepository
import com.ticketqueue.event.repository.SeatRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

/**
 * 좌석(Seat) 조회 서비스
 *
 * EventService와 분리하여 SRP를 준수한다.
 * - [getSeats]: 회차별 좌석 등급 그룹핑 조회 + Redis Cache-Aside (TTL 5분) - REQ-EVT-006
 * - [getSoldSeatIds]: SOLD 좌석 ID 조회 (캐싱 없음 - 실시간 정확도 우선)
 *
 * Redis 장애 시 try-catch로 무시하고 DB fallback을 수행하여 서비스 가용성을 유지한다.
 */
@Service
@Transactional(readOnly = true)
class SeatService(
    private val eventScheduleRepository: EventScheduleRepository,
    private val seatRepository: SeatRepository,
    private val redisTemplate: RedisTemplate<String, Any>,
    private val objectMapper: ObjectMapper,
    @Value("\${cache.seats.ttl:300}") private val seatsCacheTtl: Long
) {

    private val log = LoggerFactory.getLogger(SeatService::class.java)
    private val cacheKeyPrefix = "cache:seats:"

    /**
     * 회차별 좌석 목록을 등급 그룹핑하여 반환한다 (REQ-EVT-006, P95 < 300ms)
     *
     * Cache-Aside 전략:
     * 1. Redis Hash 캐시 조회 (등급별 필드) → Hit 시 즉시 반환
     * 2. Miss 시 → DB 조회 → 등급별 그룹핑 (VIP → S → A → B 순) → Hash 필드로 캐시 저장
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
                return SeatDto.SeatsResponse(scheduleId = scheduleId, grades = grades)
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

        try {
            val gradeMap = response.grades.associate { gradeGroup -> gradeGroup.grade.name to gradeGroup }
            redisTemplate.opsForHash<String, Any>().putAll(cacheKey, gradeMap)
            redisTemplate.expire(cacheKey, Duration.ofSeconds(seatsCacheTtl))
        } catch (e: DataAccessException) {
            log.warn("Redis cache write failed for key: $cacheKey", e)
        }

        return response
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
}
