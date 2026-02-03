package com.ticketqueue.user.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "users", schema = "user_service")
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(nullable = false)
    val email: String,

    @Column(name = "email_hash", nullable = false, unique = true)
    val emailHash: String,

    @Column(name = "password_hash", nullable = false)
    val passwordHash: String,

    @Column(nullable = false)
    val name: String,

    @Column(nullable = false)
    val phone: String,

    @Column(name = "phone_hash", nullable = false)
    val phoneHash: String,

    @Column(length = 512)
    val ci: String? = null,

    @Column(name = "ci_hash", unique = true)
    val ciHash: String? = null,

    @Column(length = 64)
    val di: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val role: UserRole = UserRole.USER,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val status: UserStatus = UserStatus.ACTIVE,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    val updatedAt: LocalDateTime? = null,

    @Column(name = "last_login_at")
    val lastLoginAt: LocalDateTime? = null,

    @Column(name = "deleted_at")
    val deletedAt: LocalDateTime? = null
)

enum class UserRole {
    USER, ADMIN
}

enum class UserStatus {
    ACTIVE, DORMANT, DELETED
}
