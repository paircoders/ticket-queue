package com.ticketqueue.common.event

import java.time.LocalDateTime
import java.util.UUID

abstract class BaseEvent(
    open val eventId: UUID = UUID.randomUUID(),
    open val eventType: String,
    open val aggregateId: UUID,
    open val aggregateType: String,
    open val version: String = "v1",
    open val timestamp: LocalDateTime = LocalDateTime.now(),
    open val metadata: EventMetadata = EventMetadata()
)

data class EventMetadata(
    val correlationId: UUID = UUID.randomUUID(),
    val causationId: UUID? = null,
    val userId: UUID? = null
)
