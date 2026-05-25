package com.ticketqueue.user.repository

import com.querydsl.jpa.impl.JPAQueryFactory
import com.ticketqueue.user.entity.QRefreshToken
import java.time.LocalDateTime
import java.util.UUID

class RefreshTokenRepositoryCustomImpl(
    private val queryFactory: JPAQueryFactory
) : RefreshTokenRepositoryCustom {

    override fun revokeAllActiveByTokenFamily(tokenFamily: UUID, now: LocalDateTime): Long {
        val token = QRefreshToken.refreshToken
        return queryFactory
            .update(token)
            .set(token.revoked, true)
            .set(token.revokedAt, now)
            .where(
                token.tokenFamily.eq(tokenFamily),
                token.revoked.isFalse
            )
            .execute()
    }

    override fun revokeAllActiveByUserId(userId: UUID, now: LocalDateTime): Long {
        val token = QRefreshToken.refreshToken
        return queryFactory
            .update(token)
            .set(token.revoked, true)
            .set(token.revokedAt, now)
            .where(
                token.user.id.eq(userId),
                token.revoked.isFalse
            )
            .execute()
    }
}
