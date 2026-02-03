package com.ticketqueue.user.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "refresh_tokens", schema = "user_service")
class RefreshToken(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(name = "token_family", nullable = false)
    val tokenFamily: UUID,

    @Column(name = "refresh_token", nullable = false, unique = true, length = 512)
    val refreshToken: String,

    @Column(name = "access_token_jti")
    val accessTokenJti: String? = null,

    @CreationTimestamp
    @Column(name = "issued_at", nullable = false, updatable = false)
    val issuedAt: LocalDateTime? = null,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: LocalDateTime,

    @Column(nullable = false)
    val revoked: Boolean = false,

    @Column(name = "revoked_at")
    val revokedAt: LocalDateTime? = null
)
