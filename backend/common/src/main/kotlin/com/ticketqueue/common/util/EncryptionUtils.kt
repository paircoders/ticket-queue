package com.ticketqueue.common.util

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac

object EncryptionUtils {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BIT = 128
    private const val IV_LENGTH_BYTE = 12
    private val secureRandom = SecureRandom()

    /**
     * AES-256-GCM 암호화
     * 결과: Base64(IV + CipherText)
     */
    fun encrypt(plainText: String, secretKey: String): String {
        val keyBytes = Base64.getDecoder().decode(secretKey)
        val key = SecretKeySpec(keyBytes, "AES")
        val iv = ByteArray(IV_LENGTH_BYTE)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BIT, iv))
        
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        
        val byteBuffer = ByteBuffer.allocate(iv.size + cipherText.size)
        byteBuffer.put(iv)
        byteBuffer.put(cipherText)
        
        return Base64.getEncoder().encodeToString(byteBuffer.array())
    }

    /**
     * AES-256-GCM 복호화
     */
    fun decrypt(encryptedText: String, secretKey: String): String {
        val keyBytes = Base64.getDecoder().decode(secretKey)
        val key = SecretKeySpec(keyBytes, "AES")
        val decoded = Base64.getDecoder().decode(encryptedText)
        
        val byteBuffer = ByteBuffer.wrap(decoded)
        val iv = ByteArray(IV_LENGTH_BYTE)
        byteBuffer.get(iv)
        
        val cipherText = ByteArray(byteBuffer.remaining())
        byteBuffer.get(cipherText)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BIT, iv))
        
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }
}

object HashUtils {
    private const val HMAC_ALGORITHM = "HmacSHA256"

    /**
     * HMAC-SHA256 해싱 (검색용)
     */
    fun hash(text: String, salt: String): String {
        val saltBytes = Base64.getDecoder().decode(salt)
        val secretKey = SecretKeySpec(saltBytes, HMAC_ALGORITHM)
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(secretKey)
        
        val hashBytes = mac.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(hashBytes)
    }
}
