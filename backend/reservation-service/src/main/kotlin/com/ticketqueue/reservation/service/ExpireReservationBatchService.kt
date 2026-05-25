package com.ticketqueue.reservation.service

import com.ticketqueue.common.event.EventMetadata
import com.ticketqueue.common.event.ReservationCancelledEvent
import com.ticketqueue.common.outbox.OutboxEventRecorder
import com.ticketqueue.reservation.entity.ReservationStatus
import com.ticketqueue.reservation.repository.ReservationRepository
import com.ticketqueue.reservation.repository.ReservationSeatRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * 선점 만료 자동 취소 배치 (REQ-RSV-007)
 *
 * ## 처리 흐름
 * 1. 매분 0초(UTC)에 PENDING + holdExpiresAt 경과 예매를 후보로 조회
 * 2. **각 예매를 개별 트랜잭션으로 분리** — 한 건의 실패가 전체 배치를 막지 않도록 격리
 * 3. 트랜잭션 내부에서 fresh re-fetch로 dirty checking + race 재검증 (동시 취소/확정과 충돌 회피)
 * 4. `reservation.cancel()` + `ReservationCancelledEvent`(reason=HOLD_EXPIRED) Outbox INSERT
 * 5. `afterCommit`에서 `hold_seats:{scheduleId}` SET에서 좌석 SREM
 *
 * ## 잠금 처리
 * Redisson `seat:hold:*` 락은 leaseTime=300s = HOLD_TTL과 동일하므로 만료 시점에 이미 자동 해제 상태.
 * 별도 unlock 호출은 불필요하며 `hold_seats` SET 정리만 책임진다.
 */
@Service
@ConditionalOnProperty(
    prefix = "reservation.expire",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class ExpireReservationBatchService(
    private val reservationRepository: ReservationRepository,
    private val reservationSeatRepository: ReservationSeatRepository,
    private val outboxEventRecorder: OutboxEventRecorder,
    private val transactionTemplate: TransactionTemplate,
    private val stringRedisTemplate: StringRedisTemplate
) {

    private val log = KotlinLogging.logger {}

    companion object {
        private const val EXPIRE_REASON = "HOLD_EXPIRED"
    }

    @Scheduled(cron = "0 * * * * *", zone = "UTC")
    fun expirePendingReservations() {
        val now = LocalDateTime.now(ZoneOffset.UTC)
        val candidates = reservationRepository.findAllByStatusAndHoldExpiresAtBefore(
            ReservationStatus.PENDING,
            now
        )
        if (candidates.isEmpty()) return

        var success = 0
        var skipped = 0
        var failed = 0
        for (reservation in candidates) {
            val reservationId = reservation.id ?: continue
            try {
                when (cancelExpiredReservation(reservationId)) {
                    CancelOutcome.CANCELLED -> success++
                    CancelOutcome.SKIPPED -> skipped++
                }
            } catch (e: Exception) {
                failed++
                log.error(e) { "Failed to expire reservation: reservationId=$reservationId" }
            }
        }
        log.info {
            "Expired reservations swept: total=${candidates.size}, cancelled=$success, skipped=$skipped, failed=$failed"
        }
    }

    private fun cancelExpiredReservation(reservationId: UUID): CancelOutcome {
        var outcome = CancelOutcome.SKIPPED
        transactionTemplate.executeWithoutResult {
            // race 재검증 — 후보 조회 이후 다른 worker가 상태/만료시각을 변경했을 수 있음
            val fresh = reservationRepository.findById(reservationId).orElse(null)
                ?: return@executeWithoutResult
            if (fresh.status != ReservationStatus.PENDING) return@executeWithoutResult
            if (!LocalDateTime.now(ZoneOffset.UTC).isAfter(fresh.holdExpiresAt)) return@executeWithoutResult

            val seatIds = reservationSeatRepository.findByReservationId(reservationId).map { it.seatId }
            fresh.cancel()
            outboxEventRecorder.record(
                ReservationCancelledEvent(
                    aggregateId = reservationId,
                    scheduleId = fresh.scheduleId,
                    seatIds = seatIds,
                    userId = fresh.userId,
                    reason = EXPIRE_REASON,
                    metadata = EventMetadata(
                        correlationId = UUID.randomUUID(),
                        causationId = null,
                        userId = fresh.userId
                    )
                )
            )
            registerAfterCommit {
                removeFromHoldSeatsSet(fresh.scheduleId, seatIds)
            }
            outcome = CancelOutcome.CANCELLED
        }
        return outcome
    }

    private fun removeFromHoldSeatsSet(scheduleId: UUID, seatIds: List<UUID>) {
        if (seatIds.isEmpty()) return
        try {
            stringRedisTemplate.opsForSet()
                .remove(RedisKeys.holdSeatsSet(scheduleId), *seatIds.map { it.toString() }.toTypedArray())
        } catch (e: Exception) {
            // hold_seats SET은 600s TTL이 보정한다 — 배치 자체는 실패 전파 금지
            log.warn(e) { "hold_seats SREM 실패 (scheduleId=$scheduleId). TTL이 보정합니다." }
        }
    }

    private fun registerAfterCommit(action: () -> Unit) {
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                action()
            }
        })
    }

    private enum class CancelOutcome { CANCELLED, SKIPPED }
}
