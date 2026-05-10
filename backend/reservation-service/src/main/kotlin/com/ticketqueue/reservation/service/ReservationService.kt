package com.ticketqueue.reservation.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.reservation.client.EventServiceClient
import com.ticketqueue.reservation.dto.ReservationDto.ChangeSeatsRequest
import com.ticketqueue.reservation.dto.ReservationDto.ChangeSeatsResponse
import com.ticketqueue.reservation.dto.ReservationDto.HoldRequest
import com.ticketqueue.reservation.dto.ReservationDto.HoldResponse
import com.ticketqueue.reservation.dto.ReservationDto.SeatStatusResponse
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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit

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
    fun holdSeats(userId: UUID, request: HoldRequest, queueToken: String): HoldResponse {
        validateQueueToken(queueToken, userId, request.scheduleId)

        // 락 목록 준비 (사용자 락 + 좌석 MultiLock)
        val userLock = redissonClient.getLock(RedisKeys.userHoldLock(userId, request.scheduleId))
        val multiSeatLock = redissonClient.getMultiLock(
            *request.seatIds.sorted().map {
                redissonClient.getLock(RedisKeys.seatHold(request.scheduleId, it))
            }.toTypedArray()
        )

        // 락과 그에 따른 에러 코드를 페어로 관리하여 순회 획득
        val lockTargets = listOf(
            userLock to ErrorCode.RESERVATION_IN_PROGRESS,
            multiSeatLock to ErrorCode.SEAT_ALREADY_HELD
        )
        return withSeatLocks(lockTargets) {
            // 수량 검증 (사용자 락 안에서 수행하여 TOCTOU 방지)
            validateSeatCount(userId, request.scheduleId, request.seatIds.size)

            checkHoldSeatsSet(request.scheduleId, request.seatIds)

            // 좌석 상세 조회 — AVAILABLE 상태 검증 + 스냅샷 (getSeatDetails 내부에서 SOLD/HOLD 거부)
            val seatDetailsResponse = eventServiceClient.getSeatDetails(request.scheduleId, request.seatIds)
            val seatDetailMap = seatDetailsResponse.seats.associateBy { it.seatId }
            if (seatDetailMap.size != request.seatIds.size) {
                throw ReservationException(ErrorCode.RESOURCE_NOT_FOUND, "요청한 좌석 정보를 찾을 수 없습니다.")
            }

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
                    request.seatIds.map { seatId -> buildReservationSeat(saved.id!!, seatId, seatDetailMap = seatDetailMap) }
                )
                // DB commit 성공 후에만 Redis 갱신 — 롤백 시 Redis 오염 없음
                registerAfterCommit {
                    try {
                        updateHoldSeatsSet(request.scheduleId, request.seatIds)
                    } catch (e: Exception) {
                        log.warn(e) { "hold_seats SET 업데이트 실패 (scheduleId=${request.scheduleId}). 배치가 보정합니다." }
                    }
                }
                saved
            } ?: throw ReservationException(ErrorCode.INTERNAL_SERVER_ERROR, "좌석 선점 저장에 실패했습니다.")

            log.info { "Seats held: userId=$userId, scheduleId=${request.scheduleId}, seatIds=${request.seatIds}, reservationId=${reservation.id}" }

            HoldResponse(
                reservationId = reservation.id!!,
                status = reservation.status,
                totalAmount = reservation.totalAmount,
                holdExpiresAt = reservation.holdExpiresAt.toUtcOffset()
            )
        }
    }

    /**
     * 좌석 변경 서비스 (REQ-RSV-002)
     *
     * ## 변경 프로세스
     * 1. 기존 + 신규 좌석 전체를 정렬하여 MultiLock 획득 — 데드락 방지
     * 2. 신규 좌석(seatsToAcquire)만 hold_seats SET 중복 확인 — 기존 좌석 오탐 방지
     * 3. DB 트랜잭션 + afterCommit 콜백으로 Redis hold_seats SET 갱신 — DB 롤백 시 Redis 오염 없음
     */
    fun changeSeats(userId: UUID, reservationId: UUID, request: ChangeSeatsRequest, queueToken: String): ChangeSeatsResponse {
        val reservation = reservationRepository.findByIdAndUserId(reservationId, userId)
            ?: throw ReservationException(ErrorCode.RESERVATION_NOT_FOUND)

        validateForChange(reservation)
        validateQueueToken(queueToken, userId, reservation.scheduleId)

        val oldSeats = reservationSeatRepository.findByReservationId(reservationId)
        val oldSeatIds = oldSeats.map { it.seatId }
        val newSeatIds = request.newSeatIds

        if (newSeatIds.size > MAX_HOLD_SEATS) {
            throw ReservationException(ErrorCode.MAX_SEATS_EXCEEDED)
        }

        // 변경 대상 분류
        val seatsToRelease = oldSeatIds.filterNot { it in newSeatIds }
        val seatsToAcquire = newSeatIds.filterNot { it in oldSeatIds }

        // 락 획득 전 수행하여 락 점유 시간 최소화 — 락 후 checkHoldSeatsSet이 Redis 상태를 재검증하므로 TOCTOU 위험 없음
        val newSeatDetailMap = fetchNewSeatDetails(reservation.scheduleId, seatsToAcquire)

        val keptSeatMap = oldSeats.filter { it.seatId in newSeatIds }.associateBy { it.seatId }
        val newTotalAmount = newSeatIds.sumOf { seatId ->
            keptSeatMap[seatId]?.price
                ?: newSeatDetailMap[seatId]?.price
                ?: throw ReservationException(ErrorCode.RESOURCE_NOT_FOUND, "좌석 상세 정보 없음: $seatId")
        }

        // 락 목록 준비 — 기존 + 신규 전체 정렬하여 데드락 방지
        val userLock = redissonClient.getLock(RedisKeys.userHoldLock(userId, reservation.scheduleId))
        // distinct: 유지 좌석이 old/new 양쪽에 포함되어 MultiLock에 중복 키가 전달되는 것을 방지
        val allSeatIds = (oldSeatIds + newSeatIds).distinct().sorted()
        val multiSeatLock = redissonClient.getMultiLock(
            *allSeatIds.map { redissonClient.getLock(RedisKeys.seatHold(reservation.scheduleId, it)) }.toTypedArray()
        )

        val lockTargets = listOf(
            userLock to ErrorCode.RESERVATION_IN_PROGRESS,
            multiSeatLock to ErrorCode.SEAT_ALREADY_HELD
        )
        return withSeatLocks(lockTargets) {
            // 다른 PENDING 예매와 합산한 좌석 한도 검증 (사용자 락 내부에서 수행)
            validateSeatCount(userId, reservation.scheduleId, newSeatIds.size, excludeReservationId = reservationId)

            // 신규 좌석만 hold_seats SET 중복 확인 (기존 좌석은 이미 보유 중이므로 제외)
            if (seatsToAcquire.isNotEmpty()) {
                checkHoldSeatsSet(reservation.scheduleId, seatsToAcquire)
            }

            val newHoldExpiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(HOLD_MINUTES)

            transactionTemplate.executeWithoutResult {
                // 락 획득 후 예매 상태 재검증 (TOCTOU 방지) + dirty checking 겸용 — SELECT 1회로 통합
                val freshReservation = reservationRepository.findByIdAndUserId(reservationId, userId)
                    ?: throw ReservationException(ErrorCode.RESERVATION_NOT_FOUND)
                if (freshReservation.status != ReservationStatus.PENDING) {
                    throw ReservationException(ErrorCode.RESERVATION_NOT_CHANGEABLE)
                }
                if (LocalDateTime.now(ZoneOffset.UTC).isAfter(freshReservation.holdExpiresAt)) {
                    throw ReservationException(ErrorCode.HOLD_EXPIRED)
                }

                reservationSeatRepository.deleteAllByReservationId(reservationId)
                reservationSeatRepository.saveAll(
                    newSeatIds.map { seatId -> buildReservationSeat(reservationId, seatId, keptSeatMap, newSeatDetailMap) }
                )
                freshReservation.updateTotalAmount(newTotalAmount)
                freshReservation.updateHoldExpiresAt(newHoldExpiresAt)

                // DB commit 성공 후에만 Redis 갱신
                registerAfterCommit {
                    if (seatsToRelease.isNotEmpty()) {
                        try {
                            removeFromHoldSeatsSet(reservation.scheduleId, seatsToRelease)
                        } catch (e: Exception) {
                            log.warn(e) { "hold_seats SREM 실패 (scheduleId=${reservation.scheduleId}). 배치가 보정합니다." }
                        }
                    }
                    if (seatsToAcquire.isNotEmpty()) {
                        try {
                            updateHoldSeatsSet(reservation.scheduleId, seatsToAcquire)
                        } catch (e: Exception) {
                            log.warn(e) { "hold_seats SADD 실패 (scheduleId=${reservation.scheduleId}). 배치가 보정합니다." }
                        }
                    }
                }
            }

            log.info { "Seats changed: userId=$userId, reservationId=$reservationId, oldSeatIds=$oldSeatIds, newSeatIds=$newSeatIds" }

            ChangeSeatsResponse(
                reservationId = reservationId,
                status = ReservationStatus.PENDING,
                newTotalAmount = newTotalAmount,
                holdExpiresAt = newHoldExpiresAt.toUtcOffset()
            )
        }
    }

    /**
     * 좌석 상태 조회
     */
    fun getSeatStatus(userId: UUID, scheduleId: UUID, queueToken: String): SeatStatusResponse {
        validateQueueToken(queueToken, userId, scheduleId)

        val soldResponse = eventServiceClient.getSoldSeats(scheduleId)

        val holdIds = stringRedisTemplate.opsForSet()
            .members(RedisKeys.holdSeatsSet(scheduleId))
            ?.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: emptyList()

        val soldSet  = soldResponse.soldSeatIds.toSet()
        val holdUniq = holdIds.toSet() - soldSet
        val available = maxOf(0L, soldResponse.totalSeats - (soldSet union holdUniq).size)

        return SeatStatusResponse(
            scheduleId = scheduleId,
            seats = SeatStatusResponse.SeatSummary(
                total     = soldResponse.totalSeats,
                available = available,
                sold      = soldSet.size,
                hold      = holdUniq.size
            ),
            sold = soldSet.toList(),
            hold = holdUniq.toList()
        )
    }

    private fun validateForChange(reservation: Reservation) {
        if (reservation.status != ReservationStatus.PENDING) {
            throw ReservationException(ErrorCode.RESERVATION_NOT_CHANGEABLE)
        }
        if (LocalDateTime.now(ZoneOffset.UTC).isAfter(reservation.holdExpiresAt)) {
            throw ReservationException(ErrorCode.HOLD_EXPIRED)
        }
    }

    private fun fetchNewSeatDetails(
        scheduleId: UUID,
        seatsToAcquire: List<UUID>
    ): Map<UUID, EventServiceClient.SeatDetailsResponse.SeatDetail> {
        if (seatsToAcquire.isEmpty()) return emptyMap()
        val response = eventServiceClient.getSeatDetails(scheduleId, seatsToAcquire)
        if (response.seats.size != seatsToAcquire.size) {
            throw ReservationException(ErrorCode.RESOURCE_NOT_FOUND, "요청한 좌석 정보를 찾을 수 없습니다.")
        }
        return response.seats.associateBy { it.seatId }
    }

    private fun validateQueueToken(token: String, userId: UUID, scheduleId: UUID) {
        val tokenJson = stringRedisTemplate.opsForValue().get(RedisKeys.queueToken(token))
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

    private fun validateSeatCount(
        userId: UUID,
        scheduleId: UUID,
        requestedCount: Int,
        excludeReservationId: UUID? = null
    ) {
        if (requestedCount > MAX_HOLD_SEATS) {
            throw ReservationException(ErrorCode.MAX_SEATS_EXCEEDED)
        }
        val pendingReservations = if (excludeReservationId != null)
            reservationRepository.findByUserIdAndScheduleIdAndStatusExcluding(userId, scheduleId, ReservationStatus.PENDING, excludeReservationId)
        else
            reservationRepository.findByUserIdAndScheduleIdAndStatus(userId, scheduleId, ReservationStatus.PENDING)

        if (pendingReservations.isEmpty()) return

        val existingCount = reservationSeatRepository.countByReservationIdIn(
            pendingReservations.mapNotNull { it.id }
        )
        if (existingCount + requestedCount > MAX_HOLD_SEATS) {
            throw ReservationException(ErrorCode.MAX_SEATS_EXCEEDED)
        }
    }

    private fun checkHoldSeatsSet(scheduleId: UUID, seatIds: List<UUID>) {
        val holdSeatsKey = RedisKeys.holdSeatsSet(scheduleId)
        val result: List<Boolean>? = stringRedisTemplate.execute { conn ->
            conn.setCommands().sMIsMember(
                holdSeatsKey.toByteArray(Charsets.UTF_8),
                *seatIds.map { it.toString().toByteArray(Charsets.UTF_8) }.toTypedArray()
            )
        }
        if (result?.any { it == true } == true) {
            throw ReservationException(ErrorCode.SEAT_ALREADY_HELD)
        }
    }

    private fun removeFromHoldSeatsSet(scheduleId: UUID, seatIds: List<UUID>) {
        stringRedisTemplate.opsForSet()
            .remove(RedisKeys.holdSeatsSet(scheduleId), *seatIds.map { it.toString() }.toTypedArray())
    }

    /**
     * hold_seats Redis SET에 좌석 ID를 추가하고 TTL을 설정한다.
     *
     * Lua 스크립트로 SADD + EXPIRE를 단일 RTT에 원자적으로 처리한다.
     * DB commit 성공 후 afterCommit 콜백에서 호출되므로 DB 롤백 시 Redis 오염이 발생하지 않는다.
     */
    private fun updateHoldSeatsSet(scheduleId: UUID, seatIds: List<UUID>) {
        val holdSeatsKey = RedisKeys.holdSeatsSet(scheduleId)
        val args = (listOf(HOLD_SEATS_SET_TTL_SECONDS.toString()) + seatIds.map { it.toString() }).toTypedArray()
        stringRedisTemplate.execute(holdSeatsSetScript, listOf(holdSeatsKey), *args)
    }

    private fun buildReservationSeat(
        reservationId: UUID,
        seatId: UUID,
        keptSeatMap: Map<UUID, ReservationSeat> = emptyMap(),
        seatDetailMap: Map<UUID, EventServiceClient.SeatDetailsResponse.SeatDetail>
    ): ReservationSeat {
        keptSeatMap[seatId]?.let { kept ->
            return ReservationSeat(reservationId = reservationId, seatId = seatId, seatNumber = kept.seatNumber, grade = kept.grade, price = kept.price)
        }
        val detail = seatDetailMap[seatId]
            ?: throw ReservationException(ErrorCode.RESOURCE_NOT_FOUND, "좌석 상세 정보 없음: $seatId")
        return ReservationSeat(reservationId = reservationId, seatId = seatId, seatNumber = detail.seatNumber, grade = detail.grade, price = detail.price)
    }

    private fun registerAfterCommit(action: () -> Unit) {
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() { action() }
        })
    }

    // lockTargets 순서대로 tryLock(waitTime=0) 획득 후 block 실행, finally에서 역순 해제
    private inline fun <T> withSeatLocks(lockTargets: List<Pair<RLock, ErrorCode>>, block: () -> T): T {
        val acquiredLocks = mutableListOf<RLock>()
        try {
            for ((lock, errorCode) in lockTargets) {
                if (!lock.tryLock(0, HOLD_TTL_SECONDS, TimeUnit.SECONDS)) {
                    throw ReservationException(errorCode)
                }
                acquiredLocks.add(lock)
            }
            return block()
        } finally {
            acquiredLocks.reversed().forEach { lock ->
                try {
                    if (lock.isHeldByCurrentThread) lock.unlock()
                } catch (e: Exception) {
                    log.warn(e) { "Failed to release lock: ${lock.name}" }
                }
            }
        }
    }

}

private fun LocalDateTime.toUtcOffset(): OffsetDateTime = atOffset(ZoneOffset.UTC)
