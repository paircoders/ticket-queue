package com.ticketqueue.user.service

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.user.config.JwtProperties
import com.ticketqueue.user.entity.UserRole
import com.ticketqueue.user.exception.UserException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * JwtTokenProvider 단위 테스트 (User Service)
 *
 * Algorithm Confusion Attack 방지 검증 (TC-026):
 * - alg=none 위조 토큰 거부 → INVALID_TOKEN (jjwt 0.12.6 기본 정책이 거부; 라이브러리 디폴트 회귀 방지용)
 * - 동일 키 HS256 다운그레이드 토큰 거부 → INVALID_TOKEN (서명은 통과하나 parseAccessTokenJti 의 alg != "HS512" 커스텀 가드가 거부)
 * - 정상 HS512 access token 은 jti 정상 반환
 */
class JwtTokenProviderTest {

    private lateinit var testSecret: String
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @BeforeEach
    fun setUp() {
        // HS512 서명을 위해 512비트(64바이트) 이상 키를 Base64 인코딩
        val keyBytes = ByteArray(64) { it.toByte() }
        testSecret = Base64.getEncoder().encodeToString(keyBytes)

        val jwtProperties = JwtProperties(
            secret = testSecret,
            accessTokenExpiry = 3_600_000L,
            refreshTokenExpiry = 1_209_600_000L
        )
        jwtTokenProvider = JwtTokenProvider(jwtProperties)
    }

    @Test
    fun `parseAccessTokenJti - alg=none 으로 위조한 토큰은 INVALID_TOKEN 예외를 던진다`() {
        // given - 서명이 없는 alg=none 토큰 (헤더.페이로드. 형태)
        val forgedToken = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4In0."

        // when & then
        val exception = shouldThrow<UserException> {
            jwtTokenProvider.parseAccessTokenJti(forgedToken)
        }
        exception.errorCode shouldBe ErrorCode.INVALID_TOKEN
    }

    @Test
    fun `parseAccessTokenJti - 동일 키로 HS256 서명한 토큰은 알고리즘 불일치로 INVALID_TOKEN 예외를 던진다`() {
        // given - 검증 키와 동일한 키로 HS256 서명 (서명은 통과, alg 체크에서 거부)
        val key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(testSecret))
        val hs256Token = Jwts.builder()
            .subject(UUID.randomUUID().toString())
            .id(UUID.randomUUID().toString())
            .claim("role", UserRole.USER.name)
            .claim("email", "test@example.com")
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + 3_600_000L))
            .signWith(key, Jwts.SIG.HS256)
            .compact()

        // when & then
        val exception = shouldThrow<UserException> {
            jwtTokenProvider.parseAccessTokenJti(hs256Token)
        }
        exception.errorCode shouldBe ErrorCode.INVALID_TOKEN
    }

    @Test
    fun `parseAccessTokenJti - 정상 HS512 access token 은 jti 를 정상 반환한다`() {
        // given
        val userId = UUID.randomUUID()
        val (token, jti) = jwtTokenProvider.generateAccessToken(userId, UserRole.USER, "test@example.com")

        // when
        val parsedJti = jwtTokenProvider.parseAccessTokenJti(token)

        // then
        parsedJti shouldBe jti
    }
}
