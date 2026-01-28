package com.ticketqueue.common.event

import java.time.LocalDateTime
import java.util.UUID

data class ReservationConfirmedEvent(
    override val eventId: UUID = UUID.randomUUID(),
    override val aggregateId: UUID,
    override val timestamp: LocalDateTime = LocalDateTime.now(),
    override val metadata: EventMetadata = EventMetadata(),
    val scheduleId: UUID,
    val seatIds: List<UUID>,
    val userId: UUID
) : BaseEvent(
    eventId = eventId,
    eventType = "ReservationConfirmed",
    aggregateId = aggregateId,
    aggregateType = "Reservation",
    timestamp = timestamp,
    metadata = metadata
)

data class ReservationCancelledEvent(
    override val eventId: UUID = UUID.randomUUID(),
    override val aggregateId: UUID,
    override val timestamp: LocalDateTime = LocalDateTime.now(),
    override val metadata: EventMetadata = EventMetadata(),
    val scheduleId: UUID,
    val seatIds: List<UUID>,
    val userId: UUID,
    val reason: String
) : BaseEvent(
    eventId = eventId,
    eventType = "ReservationCancelled",
    aggregateId = aggregateId,
    aggregateType = "Reservation",
    timestamp = timestamp,
    metadata = metadata
)
