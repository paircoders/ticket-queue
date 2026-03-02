package com.ticketqueue.user.repository

import com.ticketqueue.user.entity.RefreshToken
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import java.util.UUID

interface RefreshTokenRepository : JpaRepository<RefreshToken, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByRefreshToken(token: String): RefreshToken?
    fun findAllByTokenFamilyAndRevokedFalse(tokenFamily: UUID): List<RefreshToken>
}
