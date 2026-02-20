package com.ticketqueue.event.repository

import java.util.UUID

interface HallRepositoryCustom {
    fun existsByVenueIdAndNameExcluding(venueId: UUID, name: String, excludeHallId: UUID): Boolean
}
