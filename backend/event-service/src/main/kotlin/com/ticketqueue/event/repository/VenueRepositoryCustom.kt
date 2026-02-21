package com.ticketqueue.event.repository

import com.ticketqueue.event.entity.Venue
import java.util.UUID

interface VenueRepositoryCustom {
    fun findVenueWithHalls(venueId: UUID): Venue?
}
