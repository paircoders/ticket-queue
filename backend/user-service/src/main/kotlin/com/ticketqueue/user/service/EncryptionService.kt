package com.ticketqueue.user.service

import com.ticketqueue.common.util.EncryptionUtils
import com.ticketqueue.common.util.HashUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class EncryptionService(
    @Value("\${encryption.secret-key}") private val secretKey: String,
    @Value("\${encryption.hash-salt}") private val hashSalt: String
) {
    fun encrypt(plainText: String): String {
        return EncryptionUtils.encrypt(plainText, secretKey)
    }

    fun decrypt(encryptedText: String): String {
        return EncryptionUtils.decrypt(encryptedText, secretKey)
    }

    fun hash(text: String): String {
        return HashUtils.hash(text, hashSalt)
    }
}
