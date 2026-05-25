package com.ticketqueue.user.service

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.user.dto.UserDto
import com.ticketqueue.user.entity.UserStatus
import com.ticketqueue.user.exception.UserException
import com.ticketqueue.user.repository.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class UserService(
    private val userRepository: UserRepository,
    private val encryptionService: EncryptionService,
) {
    private val logger = KotlinLogging.logger {}

    @Transactional(readOnly = true)
    fun getProfile(userId: UUID): UserDto.ProfileResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { UserException(ErrorCode.RESOURCE_NOT_FOUND) }

        if (user.status == UserStatus.DELETED) {
            throw UserException(ErrorCode.RESOURCE_NOT_FOUND)
        }

        return UserDto.ProfileResponse.of(
            user = user,
            decryptedEmail = encryptionService.decrypt(user.email),
            decryptedName = encryptionService.decrypt(user.name),
            decryptedPhone = encryptionService.decrypt(user.phone),
        )
    }

    @Transactional
    fun updateProfile(userId: UUID, request: UserDto.UpdateProfileRequest): UserDto.UpdateProfileResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { UserException(ErrorCode.RESOURCE_NOT_FOUND) }

        if (user.status == UserStatus.DELETED) {
            throw UserException(ErrorCode.RESOURCE_NOT_FOUND)
        }

        user.name = encryptionService.encrypt(request.name)
        user.phone = encryptionService.encrypt(request.phone)
        user.phoneHash = encryptionService.hash(request.phone)

        logger.info { "Profile updated: userId=$userId" }

        return UserDto.UpdateProfileResponse.of(
            user = user,
            decryptedName = request.name,
            decryptedPhone = request.phone,
        )
    }
}
