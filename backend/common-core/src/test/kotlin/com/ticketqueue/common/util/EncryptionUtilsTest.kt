package com.ticketqueue.common.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

class EncryptionUtilsTest {

    private fun generateTestKey(): String {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val secretKey = keyGen.generateKey()
        return Base64.getEncoder().encodeToString(secretKey.encoded)
    }

    @Test
    fun `평문 암호화시 Base64 문자열 반환`() {
        // Given
        val plainText = "test message"
        val secretKey = generateTestKey()

        // When
        val encrypted = EncryptionUtils.encrypt(plainText, secretKey)

        // Then
        assertTrue(encrypted.isNotBlank(), "암호문은 비어있지 않아야 함")

        // Base64 디코딩 가능 여부 확인
        val decoded = Base64.getDecoder().decode(encrypted)
        assertTrue(decoded.size > 12, "암호문은 최소 IV(12바이트) + 암호화된 데이터를 포함해야 함")
    }

    @Test
    fun `encrypt 후 decrypt시 원본과 동일`() {
        // Given
        val plainText = "Hello, World!"
        val secretKey = generateTestKey()

        // When
        val encrypted = EncryptionUtils.encrypt(plainText, secretKey)
        val decrypted = EncryptionUtils.decrypt(encrypted, secretKey)

        // Then
        assertEquals(plainText, decrypted, "복호화 결과는 원본과 동일해야 함")
    }

    @Test
    fun `같은 평문을 2회 암호화시 다른 결과 반환 (IV 랜덤성)`() {
        // Given
        val plainText = "same message"
        val secretKey = generateTestKey()

        // When
        val encrypted1 = EncryptionUtils.encrypt(plainText, secretKey)
        val encrypted2 = EncryptionUtils.encrypt(plainText, secretKey)

        // Then
        assertNotEquals(encrypted1, encrypted2, "같은 평문도 IV가 다르면 암호문이 달라야 함")

        // 하지만 둘 다 복호화하면 같은 평문
        assertEquals(plainText, EncryptionUtils.decrypt(encrypted1, secretKey))
        assertEquals(plainText, EncryptionUtils.decrypt(encrypted2, secretKey))
    }

    @Test
    fun `빈 문자열 라운드트립`() {
        // Given
        val plainText = ""
        val secretKey = generateTestKey()

        // When
        val encrypted = EncryptionUtils.encrypt(plainText, secretKey)
        val decrypted = EncryptionUtils.decrypt(encrypted, secretKey)

        // Then
        assertEquals(plainText, decrypted, "빈 문자열도 암복호화 가능해야 함")
    }

    @Test
    fun `한글 텍스트 라운드트립`() {
        // Given
        val plainText = "안녕하세요! 한글 암호화 테스트입니다. 가나다라마바사 🎉"
        val secretKey = generateTestKey()

        // When
        val encrypted = EncryptionUtils.encrypt(plainText, secretKey)
        val decrypted = EncryptionUtils.decrypt(encrypted, secretKey)

        // Then
        assertEquals(plainText, decrypted, "한글과 이모지도 정확히 복호화되어야 함")
    }

    @Test
    fun `특수문자 라운드트립`() {
        // Given
        val plainText = "!@#$%^&*()_+-=[]{}|;':\",./<>?"
        val secretKey = generateTestKey()

        // When
        val encrypted = EncryptionUtils.encrypt(plainText, secretKey)
        val decrypted = EncryptionUtils.decrypt(encrypted, secretKey)

        // Then
        assertEquals(plainText, decrypted, "특수문자도 정확히 복호화되어야 함")
    }

    @Test
    fun `긴 문자열(1000자 이상) 라운드트립`() {
        // Given
        val plainText = "A".repeat(1500)
        val secretKey = generateTestKey()

        // When
        val encrypted = EncryptionUtils.encrypt(plainText, secretKey)
        val decrypted = EncryptionUtils.decrypt(encrypted, secretKey)

        // Then
        assertEquals(plainText, decrypted, "긴 문자열도 정확히 복호화되어야 함")
        assertEquals(1500, decrypted.length)
    }

    @Test
    fun `잘못된 키로 복호화시 예외 발생`() {
        // Given
        val plainText = "secret message"
        val correctKey = generateTestKey()
        val wrongKey = generateTestKey()

        // When
        val encrypted = EncryptionUtils.encrypt(plainText, correctKey)

        // Then
        assertThrows<AEADBadTagException> {
            EncryptionUtils.decrypt(encrypted, wrongKey)
        }
    }

    @Test
    fun `손상된 암호문 복호화시 예외 발생`() {
        // Given
        val plainText = "test message"
        val secretKey = generateTestKey()
        val encrypted = EncryptionUtils.encrypt(plainText, secretKey)

        // When - 암호문 중간 부분 변경
        val corruptedBytes = Base64.getDecoder().decode(encrypted)
        if (corruptedBytes.size > 15) {
            corruptedBytes[15] = (corruptedBytes[15] + 1).toByte()
        }
        val corruptedEncrypted = Base64.getEncoder().encodeToString(corruptedBytes)

        // Then
        assertThrows<AEADBadTagException> {
            EncryptionUtils.decrypt(corruptedEncrypted, secretKey)
        }
    }

    @Test
    fun `잘못된 Base64 문자열 복호화시 예외 발생`() {
        // Given
        val invalidBase64 = "This is not a valid base64 string!!!"
        val secretKey = generateTestKey()

        // Then
        assertThrows<IllegalArgumentException> {
            EncryptionUtils.decrypt(invalidBase64, secretKey)
        }
    }
    // TC-SEC-011: IV 영역(bytes[0..11]) 변조 시에도 GCM 인증 태그 실패 확인
    @Test
    fun `IV 영역 1바이트 변조 시 GCM 인증 태그 실패`() {
        // Given - byteBuffer[0..11] = IV, byteBuffer[12..] = CipherText+Tag
        val plainText = "test"
        val secretKey = generateTestKey()
        val encrypted = EncryptionUtils.encrypt(plainText, secretKey)

        // When - IV 내부(bytes[5]) 변조
        val bytes = Base64.getDecoder().decode(encrypted)
        bytes[5] = (bytes[5] + 1).toByte()
        val corrupted = Base64.getEncoder().encodeToString(bytes)

        // Then - IV가 변조되면 GCM 태그 검증 실패
        assertThrows<AEADBadTagException> {
            EncryptionUtils.decrypt(corrupted, secretKey)
        }
    }
}
