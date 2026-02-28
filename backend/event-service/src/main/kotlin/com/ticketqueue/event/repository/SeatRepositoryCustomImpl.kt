package com.ticketqueue.event.repository

import com.querydsl.jpa.impl.JPAQueryFactory
import com.ticketqueue.event.entity.QSeat
import com.ticketqueue.event.entity.Seat
import com.ticketqueue.event.entity.SeatStatus
import java.util.UUID

class SeatRepositoryCustomImpl(
    private val queryFactory: JPAQueryFactory
) : SeatRepositoryCustom {

    override fun existsByEventIdAndStatus(eventId: UUID, status: SeatStatus): Boolean {
        val seat = QSeat.seat
        return queryFactory
            .selectOne()
            .from(seat)
            .where(
                seat.eventSchedule.event.id.eq(eventId),
                seat.status.eq(status)
            )
            .fetchFirst() != null   // EXISTS 시맨틱: 첫 행 발견 시 즉시 반환
    }

    override fun findByScheduleIdOrderByGradeAndSeatNumber(scheduleId: UUID): List<Seat> {
        val seat = QSeat.seat
        return queryFactory
            .selectFrom(seat)
            .where(seat.eventSchedule.id.eq(scheduleId))
            .orderBy(seat.grade.asc(), seat.seatNumber.asc())
            .fetch()
    }

    override fun findSoldSeatIdsByScheduleId(scheduleId: UUID): List<UUID> {
        val seat = QSeat.seat
        return queryFactory
            .select(seat.id)
            .from(seat)
            .where(
                seat.eventSchedule.id.eq(scheduleId),
                seat.status.eq(SeatStatus.SOLD)
            )
            .fetch()
            .filterNotNull()
    }

    override fun updateStatusToSold(scheduleId: UUID, seatIds: List<UUID>): Long {
        val seat = QSeat.seat
        return queryFactory
            .update(seat)
            .set(seat.status, SeatStatus.SOLD)
            .where(
                seat.eventSchedule.id.eq(scheduleId),
                seat.id.`in`(seatIds),
                seat.status.ne(SeatStatus.SOLD)
            )
            .execute()
    }
}
