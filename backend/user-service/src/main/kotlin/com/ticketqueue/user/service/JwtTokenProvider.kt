package com.ticketqueue.user.service

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.user.config.JwtProperties
import com.ticketqueue.user.entity.UserRole
import com.ticketqueue.user.exception.UserException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * JWT 토큰 생성 담당 (User Service 전용)
 *
 * jjwt 0.12.6 API 사용. 토큰 발급만 수행.
 * 검증은 API Gateway의 JwtTokenProvider가 담당.
 */
@Component
@EnableConfigurationProperties(JwtProperties::class)
class JwtTokenProvider(private val jwtProperties: JwtProperties) {

    private val logger = KotlinLogging.logger {}

    private val secretKey by lazy {
        val decoded = try {
            Base64.getDecoder().decode(jwtProperties.secret)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("JWT secret은 유효한 Base64 형식이어야 합니다.", e)
        }
        if (decoded.size < 32) {
            throw IllegalStateException(
                "JWT secret은 최소 256비트(32바이트) 이상이어야 합니다. 현재: ${decoded.size}바이트"
            )
        }
        Keys.hmacShaKeyFor(decoded)
    }

    /**
     * Access Token 생성
     * @return Pair(token, jti) - jti는 블랙리스트 키로 사용
     */
    fun generateAccessToken(userId: UUID, role: UserRole, email: String): Pair<String, String> {
        val jti = UUID.randomUUID().toString()
        val now = Date()
        val expiry = Date(now.time + jwtProperties.accessTokenExpiry)

        val token = Jwts.builder()
            .subject(userId.toString())
            .id(jti)
            .claim("role", role.name)
            .claim("email", email)
            .issuedAt(now)
            .expiration(expiry)
            .signWith(secretKey)
            .compact()

        return Pair(token, jti)
    }

    /**
     * Refresh Token 생성
     * @return Pair(token, jti) - jti는 DB 조회 키로 사용
     */
    fun generateRefreshToken(userId: UUID): Pair<String, String> {
        val jti = UUID.randomUUID().toString()
        val now = Date()
        val expiry = Date(now.time + jwtProperties.refreshTokenExpiry)

        val token = Jwts.builder()
            .subject(userId.toString())
            .id(jti)
            .claim("type", "refresh")
            .issuedAt(now)
            .expiration(expiry)
            .signWith(secretKey)
            .compact()

        return Pair(token, jti)
    }

    /**
     * Refresh Token 서명 검증 및 userId 파싱
     * - 서명 불일치 → INVALID_TOKEN
     * - 만료 → EXPIRED_TOKEN (JWT 레벨; DB 레벨 만료는 별도 체크)
     */
    fun validateAndParseRefreshToken(token: String) {
        try {
            val claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .payload
            if (claims["type"] != "refresh") throw UserException(ErrorCode.INVALID_TOKEN)
            UUID.fromString(claims.subject) // subject가 유효한 UUID인지 검증
        } catch (e: ExpiredJwtException) {
            logger.error { "JWT token expired: ${e.javaClass.simpleName}" }
            throw UserException(ErrorCode.EXPIRED_TOKEN)
        } catch (e: UserException) {
            throw e
        } catch (e: JwtException) {
            logger.error { "JWT token invalid: ${e.javaClass.simpleName}" }
            throw UserException(ErrorCode.INVALID_TOKEN)
        } catch (e: IllegalArgumentException) {
            logger.error { "JWT subject is not a valid UUID: ${e.javaClass.simpleName} - ${e.message}" }
            throw UserException(ErrorCode.INVALID_TOKEN)
        }
    }
}
