package com.ticketqueue.gateway.security

import com.ticketqueue.gateway.config.JwtProperties
import io.github.oshai.kotlinlogging.KotlinLogging
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
 * Algorithm Confusion Attack 방지: User Service와 동일한 HS512로 알고리즘을 고정하고,
 * 파싱 후 실제 alg 헤더와 비교하여 다운그레이드 공격을 차단합니다.
 */
@Component
class JwtTokenProvider(private val jwtProperties: JwtProperties) {

    private val log = KotlinLogging.logger {}

    companion object {
        // User Service가 HS512로 고정 서명하므로 Gateway도 HS512로 고정 검증
        private const val EXPECTED_ALGORITHM = "HS512"
        private const val MIN_KEY_BYTES = 64 // HS512 최소 키 크기: 512비트(64바이트)
    }

    private val keyBytes by lazy {
        try {
            Base64.getDecoder().decode(jwtProperties.secret)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("JWT secret은 유효한 Base64 형식이어야 합니다.", e)
        }
    }

    private val secretKey by lazy {
        if (keyBytes.size < MIN_KEY_BYTES) {
            throw IllegalStateException(
                "JWT secret은 최소 512비트(64바이트) 이상이어야 합니다 (HS512 사용). 현재: ${keyBytes.size}바이트"
            )
        }
        Keys.hmacShaKeyFor(keyBytes)
    }

    // JwtParser는 불변(thread-safe)이므로 lazy로 캐시하여 매 요청마다 생성 비용 제거
    private val jwtParser by lazy {
        Jwts.parser().verifyWith(secretKey).build()
    }

    /**
     * 토큰 검증 및 claims 추출
     *
     * @throws ExpiredJwtException 토큰 만료
     * @throws JwtException 서명 불일치, 알고리즘 불일치, 파싱 오류 등
     */
    fun validateAndExtract(token: String): JwtClaims {
        val jws = jwtParser.parseSignedClaims(token)

        val actualAlg = jws.header.algorithm
        if (actualAlg != EXPECTED_ALGORITHM) {
            // Algorithm Confusion Attack 시도 가능성 — warn 레벨로 기록
            log.warn { "Algorithm mismatch detected: expected=$EXPECTED_ALGORITHM, actual=$actualAlg" }
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
