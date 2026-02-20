package com.ticketqueue.event.repository

import com.querydsl.jpa.impl.JPAQueryFactory
import com.ticketqueue.event.entity.QHall
import java.util.UUID

/**
 * Hall Custom Repository 구현체
 *
 * 과도하게 긴 Spring Data JPA 메서드명(`existsByVenueIdAndNameAndIdNot`) 대신
 * QueryDSL로 가독성 높은 쿼리를 제공한다.
 */
class HallRepositoryCustomImpl(
    private val queryFactory: JPAQueryFactory
) : HallRepositoryCustom {

    override fun existsByVenueIdAndNameExcluding(venueId: UUID, name: String, excludeHallId: UUID): Boolean {
        val hall = QHall.hall

        return queryFactory.selectOne()
            .from(hall)
            .where(
                hall.venue.id.eq(venueId),
                hall.name.eq(name),
                hall.id.ne(excludeHallId)
            )
            .fetchFirst() != null
    }
}
