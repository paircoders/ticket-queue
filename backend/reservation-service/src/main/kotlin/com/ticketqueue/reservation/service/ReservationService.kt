package com.ticketqueue.reservation.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.reservation.client.EventServiceClient
import com.ticketqueue.reservation.dto.ReservationDto.HoldRequest
import com.ticketqueue.reservation.dto.ReservationDto.HoldResponse
import com.ticketqueue.reservation.entity.Reservation
import com.ticketqueue.reservation.entity.ReservationSeat
import com.ticketqueue.reservation.entity.ReservationStatus
import com.ticketqueue.reservation.exception.ReservationException
import com.ticketqueue.reservation.repository.ReservationRepository
import com.ticketqueue.reservation.repository.ReservationSeatRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 좌석 선점 서비스 (REQ-RSV-001, REQ-RSV-005, REQ-RSV-008)
 *
 * ## 선점 프로세스
 * 1. QueueToken 검증 — queue:token:{token} Redis 직접 조회
 * 2. Redisson 분산 락 획득 — user:hold:lock:{userId}:{scheduleId} + seat:hold:{scheduleId}:{seatId}
 * 3. 기존 선점 수량 검증 — PENDING 예매의 좌석 수 합산, 사용자 락 내부에서 TOCTOU 방지 (최대 4장)
 * 4. Redis hold_seats SET 확인 — 이미 선점된 좌석 중복 방지
 * 5. 좌석 상세 조회 — Event Service 내부 API, AVAILABLE 상태 검증 + 스냅샷(seatNumber, grade, price)
 * 6. DB 저장 (TransactionTemplate) + afterCommit 콜백으로 Redis hold_seats SET 업데이트
 *    — afterCommit 사용으로 DB commit 성공 후에만 Redis 갱신 (롤백 시 Redis 오염 없음)
 *    — Redis 갱신 실패 시 경고 로그만 남기고 전파하지 않음 (hold_seats TTL 배치가 보정)
 *
 * ## 락 설계
 * tryLock(waitTime=0, leaseTime=300s)으로 락이 이미 선점 중이면 즉시 실패한다.
 * 성공/실패 모두 finally 블록에서 즉시 해제 — 선점 마커는 hold_seats SET(600s TTL)이 담당.
 */
@Service
class ReservationService(
    private val stringRedisTemplate: StringRedisTemplate,
    private val redissonClient: RedissonClient,
    private val eventServiceClient: EventServiceClient,
    private val reservationRepository: ReservationRepository,
    private val reservationSeatRepository: ReservationSeatRepository,
    private val objectMapper: ObjectMapper,
    private val transactionTemplate: TransactionTemplate
) {

    private val log = KotlinLogging.logger {}

    // SADD + EXPIRE를 단일 RTT로 원자적 처리하는 Lua 스크립트
    // ARGV[1]: TTL(초), ARGV[2..]: 좌석 ID 목록
    private val holdSeatsSetScript = RedisScript.of<Long>(
        """
        redis.call('SADD', KEYS[1], unpack(ARGV, 2))
        redis.call('EXPIRE', KEYS[1], ARGV[1])
        return 1
        """.trimIndent(),
        Long::class.java
    )

    // Queue Service 토큰 페이로드 구조 (batch-approve.lua 발급 형식과 일치)
    // issuedAt은 Lua cjson.encode가 ARGV 문자열을 JSON string으로 직렬화하므로 String? 타입
    private data class QueueTokenPayload(
        val userId: UUID,
        val scheduleId: UUID,
        val issuedAt: String? = null
    )

    companion object {
        private const val MAX_HOLD_SEATS = 4
        private const val HOLD_MINUTES = 5L
        private const val HOLD_TTL_SECONDS = 300L
        private const val HOLD_SEATS_SET_TTL_SECONDS = 600L
    }

    fun holdSeats(userId: UUID, request: HoldRequest, queueToken: String): HoldResponse {
        // 1. QueueToken 검증
        validateQueueToken(queueToken, userId, request.scheduleId)

        // 락 목록 준비 (사용자 락 + 좌석 MultiLock)
        val userLock = redissonClient.getLock("user:hold:lock:$userId:${request.scheduleId}")
        val multiSeatLock = redissonClient.getMultiLock(
            *request.seatIds.sorted().map { 
                redissonClient.getLock("seat:hold:${request.scheduleId}:$it") 
            }.toTypedArray()
        )

        // 락과 그에 따른 에러 코드를 페어로 관리하여 순회 획득
        val lockTargets = listOf(
            userLock to ErrorCode.RESERVATION_IN_PROGRESS,
            multiSeatLock to ErrorCode.SEAT_ALREADY_HELD
        )
        val acquiredLocks = mutableListOf<RLock>()

        try {
            // 모든 락 획득 시도 (waitTime=0)
            for ((lock, errorCode) in lockTargets) {
                if (!lock.tryLock(0, HOLD_TTL_SECONDS, TimeUnit.SECONDS)) {
                    throw ReservationException(errorCode)
                }
                acquiredLocks.add(lock)
            }

            // 3. 수량 검증 (사용자 락 안에서 수행하여 TOCTOU 방지)
            validateSeatCount(userId, request.scheduleId, request.seatIds.size)

            // 4. Redis hold_seats SET 중복 확인
            checkHoldSeatsSet(request.scheduleId, request.seatIds)

            // 5. 좌석 상세 조회 — AVAILABLE 상태 검증 + 스냅샷 (getSeatDetails 내부에서 SOLD/HOLD 거부)
            val seatDetailsResponse = eventServiceClient.getSeatDetails(request.scheduleId, request.seatIds)
            val seatDetailMap = seatDetailsResponse.seats.associateBy { it.seatId }
            if (seatDetailMap.size != request.seatIds.size) {
                throw ReservationException(ErrorCode.RESOURCE_NOT_FOUND, "요청한 좌석 정보를 찾을 수 없습니다.")
            }

            // 6. DB 저장 후 afterCommit 콜백으로 Redis hold_seats SET 업데이트
            val holdExpiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(HOLD_MINUTES)
            val totalAmount = seatDetailsResponse.seats.sumOf { it.price }
            val reservation = transactionTemplate.execute {
                val saved = reservationRepository.save(
                    Reservation(
                        userId = userId,
                        scheduleId = request.scheduleId,
                        eventId = seatDetailsResponse.eventId,
                        totalAmount = totalAmount,
                        holdExpiresAt = holdExpiresAt
                    )
                )
                reservationSeatRepository.saveAll(
                    request.seatIds.map { seatId ->
                        val detail = seatDetailMap[seatId]
                            ?: throw ReservationException(ErrorCode.RESOURCE_NOT_FOUND, "좌석 상세 정보 없음: $seatId")
                        ReservationSeat(
                            reservationId = saved.id!!,
                            seatId = seatId,
                            seatNumber = detail.seatNumber,
                            grade = detail.grade,
                            price = detail.price
                        )
                    }
                )
                // DB commit 성공 후에만 Redis 갱신 — 롤백 시 Redis 오염 없음
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        try {
                            updateHoldSeatsSet(request.scheduleId, request.seatIds)
                        } catch (e: Exception) {
                            log.warn(e) { "hold_seats SET 업데이트 실패 (scheduleId=${request.scheduleId}). 배치가 보정합니다." }
                        }
                    }
                })
                saved
            } ?: throw ReservationException(ErrorCode.INTERNAL_SERVER_ERROR, "좌석 선점 저장에 실패했습니다.")

            log.info { "Seats held: userId=$userId, scheduleId=${request.scheduleId}, seatIds=${request.seatIds}, reservationId=${reservation.id}" }

            return HoldResponse(
                reservationId = reservation.id!!,
                status = reservation.status,
                totalAmount = reservation.totalAmount,
                holdExpiresAt = reservation.holdExpiresAt
            )
        } finally {
            // 획득한 락을 역순으로 안전하게 해제
            acquiredLocks.reversed().forEach { lock ->
                try {
                    if (lock.isHeldByCurrentThread) lock.unlock()
                } catch (e: Exception) {
                    log.warn(e) { "Failed to release lock: ${lock.name}" }
                }
            }
        }
    }

    private fun validateQueueToken(token: String, userId: UUID, scheduleId: UUID) {
        val tokenJson = stringRedisTemplate.opsForValue().get("queue:token:$token")
            ?: throw ReservationException(ErrorCode.QUEUE_TOKEN_EXPIRED)

        val payload = try {
            objectMapper.readValue(tokenJson, QueueTokenPayload::class.java)
        } catch (e: Exception) {
            throw ReservationException(ErrorCode.QUEUE_TOKEN_INVALID)
        }

        if (payload.userId != userId || payload.scheduleId != scheduleId) {
            throw ReservationException(ErrorCode.QUEUE_TOKEN_INVALID)
        }
    }

    private fun validateSeatCount(userId: UUID, scheduleId: UUID, requestedCount: Int) {
        val pendingReservations = reservationRepository.findByUserIdAndScheduleIdAndStatus(
            userId, scheduleId, ReservationStatus.PENDING
        )
        if (pendingReservations.isEmpty()) return

        val existingCount = reservationSeatRepository.countByReservationIdIn(
            pendingReservations.mapNotNull { it.id }
        )
        if (existingCount + requestedCount > MAX_HOLD_SEATS) {
            throw ReservationException(ErrorCode.MAX_SEATS_EXCEEDED)
        }
    }

    private fun checkHoldSeatsSet(scheduleId: UUID, seatIds: List<UUID>) {
        val holdSeatsKey = "hold_seats:$scheduleId"
        seatIds.forEach { seatId ->
            if (stringRedisTemplate.opsForSet().isMember(holdSeatsKey, seatId.toString()) == true) {
                throw ReservationException(ErrorCode.SEAT_ALREADY_HELD)
            }
        }
    }

    /**
     * hold_seats Redis SET에 좌석 ID를 추가하고 TTL을 설정한다.
     *
     * Lua 스크립트로 SADD + EXPIRE를 단일 RTT에 원자적으로 처리한다.
     * DB commit 성공 후 afterCommit 콜백에서 호출되므로 DB 롤백 시 Redis 오염이 발생하지 않는다.
     */
    private fun updateHoldSeatsSet(scheduleId: UUID, seatIds: List<UUID>) {
        val holdSeatsKey = "hold_seats:$scheduleId"
        val args = (listOf(HOLD_SEATS_SET_TTL_SECONDS.toString()) + seatIds.map { it.toString() }).toTypedArray()
        stringRedisTemplate.execute(holdSeatsSetScript, listOf(holdSeatsKey), *args)
    }

}
