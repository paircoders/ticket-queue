package com.ticketqueue.event.repository

import com.querydsl.core.BooleanBuilder
import com.querydsl.jpa.impl.JPAQueryFactory
import com.ticketqueue.event.dto.EventDto
import com.ticketqueue.event.entity.Event
import com.ticketqueue.event.entity.EventStatus
import com.ticketqueue.event.entity.QEvent
import com.ticketqueue.event.entity.QEventSchedule
import com.ticketqueue.event.entity.QSeat
import com.ticketqueue.event.entity.QVenue
import com.ticketqueue.event.entity.SeatStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.time.LocalDateTime
import java.util.UUID

/**
 * Event Custom Repository 구현체
 *
 * QueryDSL을 활용하여 집계 쿼리(목록 조회)와 fetch join 쿼리(상세 조회)를 처리한다.
 *
 * 목록 조회 전략:
 * - Event + Venue LEFT JOIN + EventSchedule LEFT JOIN으로 단일 쿼리
 * - GROUP BY로 회차별 MIN/MAX startDate 집계
 * - deleted_at IS NULL 조건 항상 포함
 */
class EventRepositoryCustomImpl(
    private val queryFactory: JPAQueryFactory
) : EventRepositoryCustom {

    override fun findEventList(
        pageable: Pageable,
        status: EventStatus?,
        city: String?,
        keyword: String?
    ): Page<EventDto.ListResponse> {
        val event = QEvent.event
        val venue = QVenue.venue
        val schedule = QEventSchedule.eventSchedule

        // 집계 표현식은 변수로 저장하여 SELECT와 Tuple.get() 양쪽에서 동일 인스턴스 참조
        val minStartAt = schedule.eventStartAt.min()
        val maxStartAt = schedule.eventStartAt.max()

        val predicate = buildPredicate(event, venue, status, city, keyword)

        val results = queryFactory
            .select(event.id, event.title, event.artist, venue.name, minStartAt, maxStartAt, event.status)
            .from(event)
            .leftJoin(event.venue, venue)
            .leftJoin(schedule).on(schedule.event.eq(event))
            .where(predicate)
            .groupBy(event.id, event.title, event.artist, event.status, venue.name)
            .orderBy(minStartAt.asc().nullsLast())
            .offset(pageable.offset)
            .limit(pageable.pageSize.toLong())
            .fetch()
            .map { tuple ->
                EventDto.ListResponse(
                    id = tuple.get(event.id)!!,
                    title = tuple.get(event.title)!!,
                    artist = tuple.get(event.artist)!!,
                    venueName = tuple.get(venue.name) ?: "",
                    startDate = tuple.get(minStartAt),
                    endDate = tuple.get(maxStartAt),
                    status = tuple.get(event.status)!!
                )
            }

        val total = queryFactory
            .select(event.count())
            .from(event)
            .leftJoin(event.venue, venue)
            .where(predicate)
            .fetchOne() ?: 0L

        return PageImpl(results, pageable, total)
    }

    override fun findEventWithVenueAndHall(eventId: UUID): Event? {
        val event = QEvent.event

        return queryFactory
            .selectFrom(event)
            .leftJoin(event.venue).fetchJoin()
            .leftJoin(event.hall).fetchJoin()
            .where(
                event.id.eq(eventId),
                event.deletedAt.isNull
            )
            .fetchOne()
    }

    override fun findScheduleIdsWithAvailableSeats(scheduleIds: List<UUID>): Set<UUID> {
        if (scheduleIds.isEmpty()) return emptySet()
        val seat = QSeat.seat
        return queryFactory
            .select(seat.eventSchedule.id)
            .from(seat)
            .where(
                seat.eventSchedule.id.`in`(scheduleIds),
                seat.status.eq(SeatStatus.AVAILABLE)
            )
            .groupBy(seat.eventSchedule.id)
            .fetch()
            .filterNotNull()
            .toSet()
    }

    private fun buildPredicate(
        event: QEvent,
        venue: QVenue,
        status: EventStatus?,
        city: String?,
        keyword: String?
    ): BooleanBuilder {
        val builder = BooleanBuilder()
        builder.and(event.deletedAt.isNull)
        status?.let { builder.and(event.status.eq(it)) }
        city?.let { builder.and(venue.city.equalsIgnoreCase(it)) }
        keyword?.let {
            builder.and(
                event.title.containsIgnoreCase(it)
                    .or(event.artist.containsIgnoreCase(it))
            )
        }
        return builder
    }
}
