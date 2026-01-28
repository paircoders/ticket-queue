package com.ticketqueue.common.event

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

data class PaymentSuccessEvent(
    override val eventId: UUID = UUID.randomUUID(),
    override val aggregateId: UUID,
    override val timestamp: LocalDateTime = LocalDateTime.now(),
    override val metadata: EventMetadata = EventMetadata(),
    val reservationId: UUID,
    val paymentKey: String,
    val amount: BigDecimal,
    val paidAt: LocalDateTime
) : BaseEvent(
    eventId = eventId,
    eventType = "PaymentSuccess",
    aggregateId = aggregateId,
    aggregateType = "Payment",
    timestamp = timestamp,
    metadata = metadata
)

data class PaymentFailedEvent(
    override val eventId: UUID = UUID.randomUUID(),
    override val aggregateId: UUID,
    override val timestamp: LocalDateTime = LocalDateTime.now(),
    override val metadata: EventMetadata = EventMetadata(),
    val reservationId: UUID,
    val reason: String
) : BaseEvent(
    eventId = eventId,
    eventType = "PaymentFailed",
    aggregateId = aggregateId,
    aggregateType = "Payment",
    timestamp = timestamp,
    metadata = metadata
)
