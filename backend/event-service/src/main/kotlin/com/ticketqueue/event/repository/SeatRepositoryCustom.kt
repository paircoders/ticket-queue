package com.ticketqueue.event.repository

import com.ticketqueue.event.entity.SeatStatus
import java.util.UUID

interface SeatRepositoryCustom {
    fun existsByEventIdAndStatus(eventId: UUID, status: SeatStatus): Boolean
}
