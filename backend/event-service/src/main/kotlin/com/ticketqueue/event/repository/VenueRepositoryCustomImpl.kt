package com.ticketqueue.event.repository

import com.querydsl.jpa.impl.JPAQueryFactory
import com.ticketqueue.event.entity.QHall
import com.ticketqueue.event.entity.QVenue
import com.ticketqueue.event.entity.Venue
import java.util.UUID

/**
 * Venue Custom Repository 구현체
 *
 * N+1 문제 해결을 위해 QueryDSL의 fetchJoin을 사용하여
 * Venue와 Hall을 단일 쿼리로 조회한다.
 */
class VenueRepositoryCustomImpl(
    private val queryFactory: JPAQueryFactory
) : VenueRepositoryCustom {

    override fun findVenueWithHalls(venueId: UUID): Venue? {
        val venue = QVenue.venue
        val hall = QHall.hall

        return queryFactory.selectFrom(venue)
            .leftJoin(venue.halls, hall).fetchJoin()
            .where(venue.id.eq(venueId))
            .fetchOne()
    }
}
