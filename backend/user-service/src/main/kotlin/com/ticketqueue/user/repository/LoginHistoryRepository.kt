package com.ticketqueue.user.repository

import com.ticketqueue.user.entity.LoginHistory
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LoginHistoryRepository : JpaRepository<LoginHistory, UUID> {
}
