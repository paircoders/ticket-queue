package com.ticketqueue.event.repository

import com.ticketqueue.event.entity.EventSchedule
import com.ticketqueue.event.entity.ScheduleStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.UUID

@Repository
interface EventScheduleRepository : JpaRepository<EventSchedule, UUID>, EventScheduleRepositoryCustom {
    fun findByEventIdOrderByPlaySequence(eventId: UUID): List<EventSchedule>

    /**
     * 주어진 공연의 회차 중 판매가 이미 시작된 회차가 존재하는지 확인한다.
     * updateEvent의 hasSaleStarted 체크를 위해 전체 목록 로드 대신 EXISTS 쿼리 1개로 처리한다.
     */
    fun existsByEventIdAndSaleStartAtLessThanEqual(eventId: UUID, dateTime: LocalDateTime): Boolean

    /**
     * 동일 공연에 동일 회차 순번이 이미 존재하는지 확인한다.
     * createSchedule의 중복 순번 방지를 위해 사용한다.
     */
    fun existsByEventIdAndPlaySequence(eventId: UUID, playSequence: Int): Boolean

    /**
     * 좌석 선점이 활성화된 회차 ID 목록을 조회한다 (SeatConsistencyScheduler 전용)
     *
     * UPCOMING/ONGOING 상태 회차만 대상으로 한다.
     * Redis hold_seats SET이 존재할 수 있는 회차만 검증하면 충분하다.
     */
    @Query("SELECT s.id FROM EventSchedule s WHERE s.status IN :statuses")
    fun findIdsByStatusIn(statuses: Collection<ScheduleStatus>): List<UUID>
}
