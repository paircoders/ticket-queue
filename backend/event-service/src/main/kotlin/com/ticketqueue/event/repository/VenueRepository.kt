package com.ticketqueue.event.repository

import com.ticketqueue.event.entity.Venue
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface VenueRepository : JpaRepository<Venue, UUID> {
    fun findByCity(city: String, pageable: Pageable): Page<Venue>
}
