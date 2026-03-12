package com.ticketqueue.event.repository

import java.time.LocalDateTime
import java.util.UUID

interface EventScheduleRepositoryCustom {
    fun findCleanupTargetScheduleIds(cutoffTime: LocalDateTime): List<UUID>
}
