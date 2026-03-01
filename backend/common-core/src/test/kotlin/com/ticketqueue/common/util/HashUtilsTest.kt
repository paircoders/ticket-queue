package com.ticketqueue.common.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.security.SecureRandom
import java.util.Base64

class HashUtilsTest {

    private fun generateSalt(): String {
        val saltBytes = ByteArray(32)
        SecureRandom().nextBytes(saltBytes)
        return Base64.getEncoder().encodeToString(saltBytes)
    }

    @Test
    fun `해싱 결과는 Base64 문자열 반환`() {
        // given
        val text = "testPassword123"
        val salt = generateSalt()

        // when
        val hashed = HashUtils.hash(text, salt)

        // then
        assertNotNull(hashed)
        assertTrue(hashed.isNotEmpty())
        assertDoesNotThrow {
            Base64.getDecoder().decode(hashed)
        }
    }

    @Test
    fun `동일 입력과 salt로 동일 결과 생성 (결정론적)`() {
        // given
        val text = "testPassword123"
        val salt = generateSalt()

        // when
        val hashed1 = HashUtils.hash(text, salt)
        val hashed2 = HashUtils.hash(text, salt)

        // then
        assertEquals(hashed1, hashed2)
    }

    @Test
    fun `다른 텍스트는 다른 해시 결과 생성`() {
        // given
        val text1 = "password1"
        val text2 = "password2"
        val salt = generateSalt()

        // when
        val hashed1 = HashUtils.hash(text1, salt)
        val hashed2 = HashUtils.hash(text2, salt)

        // then
        assertNotEquals(hashed1, hashed2)
    }

    @Test
    fun `같은 텍스트, 다른 salt는 다른 해시 결과 생성`() {
        // given
        val text = "testPassword123"
        val salt1 = generateSalt()
        val salt2 = generateSalt()

        // when
        val hashed1 = HashUtils.hash(text, salt1)
        val hashed2 = HashUtils.hash(text, salt2)

        // then
        assertNotEquals(hashed1, hashed2)
    }

    @Test
    fun `빈 문자열 해싱은 정상 반환`() {
        // given
        val text = ""
        val salt = generateSalt()

        // when
        val hashed = HashUtils.hash(text, salt)

        // then
        assertNotNull(hashed)
        assertTrue(hashed.isNotEmpty())
        assertDoesNotThrow {
            Base64.getDecoder().decode(hashed)
        }
    }

    @Test
    fun `한글 해싱은 정상 반환`() {
        // given
        val text = "한글비밀번호테스트"
        val salt = generateSalt()

        // when
        val hashed = HashUtils.hash(text, salt)

        // then
        assertNotNull(hashed)
        assertTrue(hashed.isNotEmpty())
        assertDoesNotThrow {
            Base64.getDecoder().decode(hashed)
        }
    }

    @Test
    fun `SHA256 결과 길이는 Base64로 44자`() {
        // given
        val text = "testPassword123"
        val salt = generateSalt()

        // when
        val hashed = HashUtils.hash(text, salt)

        // then
        // HMAC-SHA256 = 32 bytes -> Base64 encoding = 44 characters
        assertEquals(44, hashed.length)
    }
}