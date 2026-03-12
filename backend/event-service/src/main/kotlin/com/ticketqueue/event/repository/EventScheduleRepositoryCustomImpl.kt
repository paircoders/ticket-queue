package com.ticketqueue.event.repository

import com.querydsl.jpa.impl.JPAQueryFactory
import com.ticketqueue.event.entity.QEventSchedule
import com.ticketqueue.event.entity.ScheduleStatus
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Transactional(readOnly = true)
class EventScheduleRepositoryCustomImpl(
    private val queryFactory: JPAQueryFactory
) : EventScheduleRepositoryCustom {

    override fun findCleanupTargetScheduleIds(cutoffTime: LocalDateTime): List<UUID> {
        val schedule = QEventSchedule.eventSchedule
        return queryFactory
            .select(schedule.id)
            .from(schedule)
            .where(
                schedule.status.`in`(ScheduleStatus.ENDED, ScheduleStatus.CANCELLED),
                schedule.eventEndAt.lt(cutoffTime)
            )
            .limit(1000)
            .fetch()
    }
}
