package com.ticketqueue.gateway.support

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * 통합 테스트 공통 헬퍼
 *
 * JwtAuthenticationWebFilterTest, QueueTokenWebFilterTest 등에서 공유.
 */
fun createValidToken(
    jwtSecret: String,
    userId: String,
    role: String,
    expirationMs: Long = 3_600_000L, // 1시간
): String {
    val decoded = Base64.getDecoder().decode(jwtSecret)
    val key = Keys.hmacShaKeyFor(decoded)

    return Jwts.builder()
        .subject(userId)
        .claim("role", role)
        .id(UUID.randomUUID().toString())
        .issuedAt(Date())
        .expiration(Date(System.currentTimeMillis() + expirationMs))
        .signWith(key, Jwts.SIG.HS512)
        .compact()
}
