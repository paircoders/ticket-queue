package com.ticketqueue.event.repository

import com.ticketqueue.event.entity.Venue
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface VenueRepository : JpaRepository<Venue, UUID>
