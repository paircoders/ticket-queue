package com.ticketqueue.user.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.KeyGenerator

class EncryptionServiceTest {

    private lateinit var encryptionService: EncryptionService
    private lateinit var testKey: String
    private lateinit var testSalt: String

    @BeforeEach
    fun setUp() {
        // AES-256 키 생성 (32바이트)
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val secretKey = keyGen.generateKey()
        testKey = Base64.getEncoder().encodeToString(secretKey.encoded)

        // Salt 생성 (32바이트)
        val saltBytes = ByteArray(32)
        SecureRandom().nextBytes(saltBytes)
        testSalt = Base64.getEncoder().encodeToString(saltBytes)

        // 생성자로 직접 인스턴스 생성 (순수 유닛 테스트)
        encryptionService = EncryptionService(testKey, testSalt)
    }

    @Test
    fun `encrypt then decrypt should return original text`() {
        // given
        val originalText = "홍길동"

        // when
        val encrypted = encryptionService.encrypt(originalText)
        val decrypted = encryptionService.decrypt(encrypted)

        // then
        assertEquals(originalText, decrypted)
    }

    @Test
    fun `encrypt should produce different results for same input due to random IV`() {
        // given
        val plainText = "test@example.com"

        // when
        val encrypted1 = encryptionService.encrypt(plainText)
        val encrypted2 = encryptionService.encrypt(plainText)

        // then
        assertNotEquals(encrypted1, encrypted2, "두 암호화 결과는 IV 랜덤성으로 인해 달라야 함")

        // 하지만 복호화 결과는 동일
        val decrypted1 = encryptionService.decrypt(encrypted1)
        val decrypted2 = encryptionService.decrypt(encrypted2)
        assertEquals(plainText, decrypted1)
        assertEquals(plainText, decrypted2)
    }

    @Test
    fun `hash should return consistent result for same input`() {
        // given
        val inputText = "010-1234-5678"

        // when
        val hash1 = encryptionService.hash(inputText)
        val hash2 = encryptionService.hash(inputText)

        // then
        assertEquals(hash1, hash2, "동일 입력에 대한 해시는 결정론적이어야 함")
    }

    @Test
    fun `hash should return different results for different inputs`() {
        // given
        val input1 = "010-1234-5678"
        val input2 = "010-9876-5432"

        // when
        val hash1 = encryptionService.hash(input1)
        val hash2 = encryptionService.hash(input2)

        // then
        assertNotEquals(hash1, hash2, "다른 입력에 대한 해시는 달라야 함")
    }
}
