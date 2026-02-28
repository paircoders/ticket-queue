package com.ticketqueue.user.service

import com.ticketqueue.user.entity.LoginHistory
import com.ticketqueue.user.entity.LoginMethod
import com.ticketqueue.user.entity.User
import com.ticketqueue.user.repository.LoginHistoryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class LoginHistoryRecorder(
    private val loginHistoryRepository: LoginHistoryRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordFailureWithoutUser(ipAddress: String, userAgent: String, reason: String) {
        loginHistoryRepository.save(
            LoginHistory(
                user = null,
                loginMethod = LoginMethod.EMAIL,
                success = false,
                failureReason = reason,
                ipAddress = ipAddress.ifEmpty { null },
                userAgent = userAgent.ifEmpty { null },
            )
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordFailure(user: User, ipAddress: String, userAgent: String, reason: String) {
        loginHistoryRepository.save(
            LoginHistory(
                user = user,
                loginMethod = LoginMethod.EMAIL,
                success = false,
                failureReason = reason,
                ipAddress = ipAddress.ifEmpty { null },
                userAgent = userAgent.ifEmpty { null },
            )
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordSuccess(user: User, ipAddress: String, userAgent: String) {
        user.lastLoginAt = LocalDateTime.now()
        loginHistoryRepository.save(
            LoginHistory(
                user = user,
                loginMethod = LoginMethod.EMAIL,
                success = true,
                ipAddress = ipAddress.ifEmpty { null },
                userAgent = userAgent.ifEmpty { null },
            )
        )
    }
}
