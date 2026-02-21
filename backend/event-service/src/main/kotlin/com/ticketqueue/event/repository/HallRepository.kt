package com.ticketqueue.event.repository

import com.ticketqueue.event.entity.Hall
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface HallRepository : JpaRepository<Hall, UUID>, HallRepositoryCustom {
    fun findByVenueId(venueId: UUID): List<Hall>
    fun findByVenueIdAndId(venueId: UUID, id: UUID): Hall?
    fun existsByVenueId(venueId: UUID): Boolean
    fun existsByVenueIdAndName(venueId: UUID, name: String): Boolean
}
