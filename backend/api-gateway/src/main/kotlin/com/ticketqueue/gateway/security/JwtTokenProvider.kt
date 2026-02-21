package com.ticketqueue.gateway.security

import com.ticketqueue.gateway.config.JwtProperties
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.util.Base64

/**
 * JWT 파싱 및 서명 검증 담당
 *
 * jjwt 0.12.6 API 사용.
 * Gateway에서는 검증 + claims 추출만 수행 (토큰 발급은 User Service 담당).
 */
@Component
class JwtTokenProvider(private val jwtProperties: JwtProperties) {

    private val secretKey by lazy {
        val decoded = Base64.getDecoder().decode(jwtProperties.secret)
        Keys.hmacShaKeyFor(decoded)
    }

    /**
     * 토큰 검증 및 claims 추출
     *
     * @throws ExpiredJwtException 토큰 만료
     * @throws JwtException 서명 불일치, 파싱 오류 등
     */
    fun validateAndExtract(token: String): JwtClaims {
        val claims = Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)
            .payload

        return JwtClaims(
            userId = claims.subject,
            role = claims["role"] as String,
            jti = claims.id,
        )
    }
}

/**
 * JWT에서 추출한 사용자 정보
 *
 * @property userId 사용자 ID (JWT sub 클레임)
 * @property role 사용자 권한 (USER / ADMIN)
 * @property jti JWT ID (블랙리스트 조회 키)
 */
data class JwtClaims(
    val userId: String,
    val role: String,
    val jti: String,
)
