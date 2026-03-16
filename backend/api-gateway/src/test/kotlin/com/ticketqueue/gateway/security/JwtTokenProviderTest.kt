package com.ticketqueue.gateway.security

import com.ticketqueue.gateway.config.JwtProperties
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.UnsupportedJwtException
import io.jsonwebtoken.security.Keys
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * JwtTokenProvider 단위 테스트
 *
 * Algorithm Confusion Attack 방지 검증:
 * - 유효한 HS512 토큰 파싱 성공
 * - HS256 다운그레이드 토큰 거부
 * - alg:none 토큰 거부
 * - sub 클레임 누락 시 실패
 */
class JwtTokenProviderTest {

    private lateinit var testSecret: String

    private lateinit var provider: JwtTokenProvider

    @BeforeEach
    fun setUp() {
        val bytes = ByteArray(64).also { java.security.SecureRandom().nextBytes(it) }
        testSecret = Base64.getEncoder().encodeToString(bytes)
        val props = JwtProperties(secret = testSecret)
        provider = JwtTokenProvider(props)
    }

    private fun buildToken(
        secret: String = testSecret,
        subject: String? = "user-123",
        role: String? = "USER",
        jti: String = UUID.randomUUID().toString(),
        expirationMs: Long = 3_600_000L,
        algorithm: io.jsonwebtoken.security.MacAlgorithm = Jwts.SIG.HS512,
    ): String {
        val key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret))
        val builder = Jwts.builder()
            .id(jti)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + expirationMs))
            .signWith(key, algorithm)

        subject?.let { builder.subject(it) }
        role?.let { builder.claim("role", it) }

        return builder.compact()
    }

    @Test
    fun `유효한 HS512 토큰 파싱 성공`() {
        val jti = UUID.randomUUID().toString()
        val token = buildToken(jti = jti)

        val claims = provider.validateAndExtract(token)

        claims.userId shouldBe "user-123"
        claims.role shouldBe "USER"
        claims.jti shouldBe jti
    }

    @Test
    fun `다른 키로 서명한 HS256 토큰은 서명 불일치로 거부`() {
        // 32바이트 키로 HS256 서명 (공격자가 alg를 낮춰 서명한 토큰 시뮬레이션)
        val hs256Secret = Base64.getEncoder().encodeToString(
            "test-secret-key-for-hs256-attack!!".toByteArray()
        )
        val token = buildToken(secret = hs256Secret, algorithm = Jwts.SIG.HS256)

        // 서명 키가 다르므로 JJWT가 서명 검증 실패를 먼저 던짐
        shouldThrow<JwtException> {
            provider.validateAndExtract(token)
        }
    }

    @Test
    fun `동일 키로 HS256 서명한 토큰은 알고리즘 불일치로 거부`() {
        // 동일한 64바이트 키로 HS256 서명 (algorithm confusion attack)
        val token = buildToken(algorithm = Jwts.SIG.HS256)

        val ex = shouldThrow<JwtException> {
            provider.validateAndExtract(token)
        }
        ex.message shouldBe "Algorithm mismatch detected"
    }

    @Test
    fun `동일 키로 HS384 서명한 토큰은 알고리즘 불일치로 거부`() {
        // 동일한 64바이트 키로 HS384 서명 (algorithm confusion attack — 중간 단계)
        val token = buildToken(algorithm = Jwts.SIG.HS384)

        val ex = shouldThrow<JwtException> {
            provider.validateAndExtract(token)
        }
        ex.message shouldBe "Algorithm mismatch detected"
    }

    @Test
    fun `alg none 토큰은 UnsupportedJwtException 발생`() {
        // 서명 없는 토큰 문자열 (헤더.페이로드. 형태) — 동적 생성으로 하드코딩 제거
        val header = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"alg":"none"}""".toByteArray())
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"user-123","role":"USER"}""".toByteArray())
        val unsignedToken = "$header.$payload."

        shouldThrow<UnsupportedJwtException> {
            provider.validateAndExtract(unsignedToken)
        }
    }

    @Test
    fun `sub 클레임 누락 시 JwtException 발생`() {
        val token = buildToken(subject = null)

        val ex = shouldThrow<JwtException> {
            provider.validateAndExtract(token)
        }
        ex.message shouldBe "Missing sub claim"
    }

    @Test
    fun `role 클레임 누락 시 JwtException 발생`() {
        val token = buildToken(role = null)

        val ex = shouldThrow<JwtException> {
            provider.validateAndExtract(token)
        }
        ex.message shouldBe "Missing role claim"
    }

    @Test
    fun `만료된 토큰은 ExpiredJwtException 발생`() {
        val token = buildToken(expirationMs = -3_600_000L)

        shouldThrow<ExpiredJwtException> {
            provider.validateAndExtract(token)
        }
    }

    @Test
    fun `jti 클레임 누락 시 JwtException 발생`() {
        val key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(testSecret))
        val token = Jwts.builder()
            .subject("user-123")
            .claim("role", "USER")
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + 3_600_000L))
            .signWith(key, Jwts.SIG.HS512)
            .compact()

        val ex = shouldThrow<JwtException> {
            provider.validateAndExtract(token)
        }
        ex.message shouldBe "Missing jti claim"
    }

    @Test
    fun `빈 문자열 토큰은 IllegalArgumentException 발생`() {
        shouldThrow<IllegalArgumentException> {
            provider.validateAndExtract("")
        }
    }

    @Test
    fun `malformed 토큰은 JwtException 발생`() {
        shouldThrow<JwtException> {
            provider.validateAndExtract("notavalidjwt")
        }
    }
}
