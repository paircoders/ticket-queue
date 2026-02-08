package com.ticketqueue.user.repository

import com.ticketqueue.user.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional
import java.util.UUID

@Repository
interface UserRepository : JpaRepository<User, UUID> {
    fun existsByEmailHash(emailHash: String): Boolean
    fun existsByCiHash(ciHash: String): Boolean
}
