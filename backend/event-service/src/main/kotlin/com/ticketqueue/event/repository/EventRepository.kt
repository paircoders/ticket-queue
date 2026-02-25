package com.ticketqueue.event.repository

import com.ticketqueue.event.entity.Event
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface EventRepository : JpaRepository<Event, UUID>, EventRepositoryCustom {
    fun existsByVenueId(venueId: UUID): Boolean
    fun existsByHallId(hallId: UUID): Boolean
    fun findByIdAndDeletedAtIsNull(id: UUID): Event?
}
