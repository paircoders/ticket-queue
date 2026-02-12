package com.ticketqueue.event.repository

import com.ticketqueue.event.entity.EventSchedule
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface EventScheduleRepository : JpaRepository<EventSchedule, UUID>
