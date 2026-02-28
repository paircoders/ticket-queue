package com.ticketqueue.user.repository

import com.ticketqueue.user.entity.RefreshToken
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface RefreshTokenRepository : JpaRepository<RefreshToken, UUID> {
    fun findByRefreshToken(token: String): RefreshToken?
    fun findAllByTokenFamilyAndRevokedFalse(tokenFamily: UUID): List<RefreshToken>
}
