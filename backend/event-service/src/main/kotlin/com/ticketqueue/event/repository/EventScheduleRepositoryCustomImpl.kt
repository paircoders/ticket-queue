package com.ticketqueue.event.repository

import com.querydsl.jpa.impl.JPAQueryFactory
import com.ticketqueue.event.entity.EventSchedule
import com.ticketqueue.event.entity.QEvent
import com.ticketqueue.event.entity.QEventSchedule
import com.ticketqueue.event.entity.ScheduleStatus
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

@Transactional(readOnly = true)
class EventScheduleRepositoryCustomImpl(
    private val queryFactory: JPAQueryFactory
) : EventScheduleRepositoryCustom {

    override fun findCleanupTargetScheduleIds(
        cutoffTime: LocalDateTime,
        afterEventEndAt: LocalDateTime?,
        afterId: UUID?
    ): List<ScheduleCleanupCursor> {
        val schedule = QEventSchedule.eventSchedule

        // keyset pagination: (eventEndAt > after) OR (eventEndAt = after AND id > afterId)
        val cursorPredicate = if (afterEventEndAt != null && afterId != null) {
            schedule.eventEndAt.gt(afterEventEndAt)
                .or(schedule.eventEndAt.eq(afterEventEndAt).and(schedule.id.gt(afterId)))
        } else null

        return queryFactory
            .select(schedule.id, schedule.eventEndAt)
            .from(schedule)
            .where(
                schedule.status.`in`(ScheduleStatus.ENDED, ScheduleStatus.CANCELLED),
                schedule.eventEndAt.lt(cutoffTime),
                cursorPredicate
            )
            .orderBy(schedule.eventEndAt.asc(), schedule.id.asc())
            .limit(1000)
            .fetch()
            .map { tuple ->
                ScheduleCleanupCursor(
                    id = tuple.get(schedule.id)!!,
                    eventEndAt = tuple.get(schedule.eventEndAt)!!
                )
            }
    }

    override fun findByIdWithEvent(id: UUID): Optional<EventSchedule> {
        val schedule = QEventSchedule.eventSchedule
        val event = QEvent.event
        return Optional.ofNullable(
            queryFactory
                .selectFrom(schedule)
                .join(schedule.event, event).fetchJoin()
                .where(schedule.id.eq(id))
                .fetchOne()
        )
    }
}
