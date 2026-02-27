package com.ticketqueue.event.entity

enum class EventStatus {
    PREPARING, OPEN, ENDED, CANCELLED;

    private companion object {
        val allowedTransitions = mapOf(
            PREPARING to setOf(OPEN, CANCELLED),
            OPEN to setOf(ENDED, CANCELLED),
            ENDED to emptySet<EventStatus>(),
            CANCELLED to emptySet<EventStatus>()
        )
    }

    fun canTransitionTo(target: EventStatus): Boolean =
        allowedTransitions[this]?.contains(target) ?: false
}

enum class ScheduleStatus { UPCOMING, ONGOING, ENDED, CANCELLED }
enum class SeatGrade { VIP, S, A, B }
enum class SeatStatus { AVAILABLE, SOLD }
