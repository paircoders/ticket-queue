package com.ticketqueue.user.repository

import java.time.LocalDateTime
import java.util.UUID

interface RefreshTokenRepositoryCustom {
    fun revokeAllActiveByTokenFamily(tokenFamily: UUID, now: LocalDateTime): Long

    fun revokeAllActiveByUserId(userId: UUID, now: LocalDateTime): Long
}
