package com.ticketqueue.gateway.security

import com.ticketqueue.gateway.config.JwtProperties
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.security.SecureRandom
import java.util.Base64
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

class JwtTokenProviderTest : DescribeSpec({

    // 64바이트 랜덤 키 (HMAC-SHA512)
    val rawSecret = ByteArray(64).also { SecureRandom().nextBytes(it) }
    val base64Secret: String = Base64.getEncoder().encodeToString(rawSecret)
    val secretKey: SecretKey = Keys.hmacShaKeyFor(rawSecret)

    val properties = JwtProperties(secret = base64Secret)
    val provider = JwtTokenProvider(properties)

    fun buildToken(
        subject: String = "user-123",
        role: String? = "USER",
        jti: String? = UUID.randomUUID().toString(),
        expirationMs: Long = 3_600_000L,
        key: SecretKey = secretKey
    ): String {
        val builder = Jwts.builder()
            .subject(subject)
            .expiration(Date(System.currentTimeMillis() + expirationMs))
            .signWith(key)
        jti?.let { builder.id(it) }
        role?.let { builder.claim("role", it) }
        return builder.compact()
    }

    describe("JwtTokenProvider.validateAndExtract()") {

        context("정상 토큰") {
            it("JwtClaims 올바르게 추출") {
                val jti = UUID.randomUUID().toString()
                val token = buildToken(subject = "user-42", role = "USER", jti = jti)
                val claims = provider.validateAndExtract(token)
                claims.userId shouldBe "user-42"
                claims.role shouldBe "USER"
                claims.jti shouldBe jti
            }

            it("ADMIN role도 정상 추출") {
                val token = buildToken(subject = "admin-1", role = "ADMIN")
                val claims = provider.validateAndExtract(token)
                claims.role shouldBe "ADMIN"
            }
        }

        context("만료 토큰") {
            it("ExpiredJwtException 발생") {
                val token = buildToken(expirationMs = -1000L)
                shouldThrow<ExpiredJwtException> {
                    provider.validateAndExtract(token)
                }
            }
        }

        context("잘못된 서명") {
            it("JwtException 발생") {
                val otherKey = Keys.hmacShaKeyFor(ByteArray(64).also { SecureRandom().nextBytes(it) })
                val token = buildToken(key = otherKey)
                shouldThrow<JwtException> {
                    provider.validateAndExtract(token)
                }
            }
        }

        context("role 클레임 없는 토큰") {
            it("JwtException(Missing role claim) 발생") {
                val token = buildToken(role = null)
                shouldThrow<JwtException> {
                    provider.validateAndExtract(token)
                }
            }
        }

        context("jti 없는 토큰") {
            it("JwtException(Missing jti claim) 발생") {
                val token = buildToken(jti = null)
                shouldThrow<JwtException> {
                    provider.validateAndExtract(token)
                }
            }
        }

        context("완전히 잘못된 문자열") {
            it("JwtException 발생") {
                shouldThrow<JwtException> {
                    provider.validateAndExtract("not.a.jwt")
                }
            }
        }
    }
})
