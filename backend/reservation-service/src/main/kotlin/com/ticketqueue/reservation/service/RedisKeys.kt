package com.ticketqueue.reservation.service

import java.util.UUID

internal object RedisKeys {
    fun userHoldLock(userId: UUID, scheduleId: UUID) = "user:hold:lock:$userId:$scheduleId"
    fun seatHold(scheduleId: UUID, seatId: UUID) = "seat:hold:$scheduleId:$seatId"
    fun holdSeatsSet(scheduleId: UUID) = "hold_seats:$scheduleId"
    fun queueToken(token: String) = "queue:token:$token"
}
