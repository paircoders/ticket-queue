package com.ticketqueue.event.repository

import com.querydsl.jpa.impl.JPAQueryFactory
import com.ticketqueue.event.entity.QSeat
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
}
