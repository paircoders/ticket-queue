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
 *
 * Algorithm Confusion Attack 방지: 키 크기 기반으로 예상 알고리즘을 결정하고,
 * 파싱 후 실제 alg 헤더와 비교하여 다운그레이드 공격을 차단합니다.
 */
@Component
class JwtTokenProvider(private val jwtProperties: JwtProperties) {

    private val keyBytes by lazy {
        Base64.getDecoder().decode(jwtProperties.secret)
    }

    private val secretKey by lazy {
        Keys.hmacShaKeyFor(keyBytes)
    }

    private val expectedAlgorithm by lazy {
        when {
            keyBytes.size >= 64 -> "HS512"
            keyBytes.size >= 48 -> "HS384"
            else -> "HS256"
        }
    }

    /**
     * 토큰 검증 및 claims 추출
     *
     * @throws ExpiredJwtException 토큰 만료
     * @throws JwtException 서명 불일치, 알고리즘 불일치, 파싱 오류 등
     */
    fun validateAndExtract(token: String): JwtClaims {
        val jws = Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)

        val actualAlg = jws.header.algorithm
        if (actualAlg != expectedAlgorithm) {
            throw JwtException("Algorithm mismatch detected")
        }

        val claims = jws.payload
        val userId = claims.subject ?: throw JwtException("Missing sub claim")
        val role = claims.get("role", String::class.java)
            ?: throw JwtException("Missing role claim")
        val jti = claims.id ?: throw JwtException("Missing jti claim")

        return JwtClaims(userId = userId, role = role, jti = jti)
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
