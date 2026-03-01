package com.ticketqueue.common.external.portone

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * PortOne V2 Access Token 관리 및 자동 갱신 서비스
 */
@Service
@ConditionalOnProperty(prefix = "external.portone", name = ["enabled"], havingValue = "true")
class PortoneTokenService(
    private val portoneClient: PortoneFeignClient,
    private val portoneProperties: PortoneProperties
) {
    // 토큰 상태를 하나의 불변 객체로 관리하여 원자성 확보
    private data class TokenState(
        val accessToken: String,
        val refreshToken: String?,
        val expiryTime: Instant
    )

    private val tokenState = AtomicReference<TokenState?>()

    /**
     * 유효한 Access Token을 반환 (필요 시 자동 갱신)
     * @return Bearer {ACCESS_TOKEN}
     */
    fun getAccessToken(): String {
        val currentState = tokenState.get()

        // 1. 유효한 토큰이 있으면 바로 반환
        if (currentState != null && !isAboutToExpire(currentState)) {
            return "Bearer ${currentState.accessToken}"
        }

        // 2. 갱신이 필요한 경우 동기화하여 중복 갱신 방지
        synchronized(this) {
            val latestState = tokenState.get()
            // Double-check: 대기하는 동안 다른 스레드가 이미 갱신했을 수 있음
            if (latestState != null && !isAboutToExpire(latestState)) {
                return "Bearer ${latestState.accessToken}"
            }

            val newState = refreshOrLogin(latestState)
            tokenState.set(newState)
            return "Bearer ${newState.accessToken}"
        }
    }

    private fun isAboutToExpire(state: TokenState): Boolean {
        // 만료 2분 전부터 미리 갱신 시도
        return Instant.now().isAfter(state.expiryTime.minusSeconds(120))
    }

    private fun refreshOrLogin(state: TokenState?): TokenState {
        return try {
            if (state?.refreshToken != null) {
                val response = portoneClient.refreshToken(PortoneRefreshRequest(state.refreshToken))
                createState(response, state.refreshToken)
            } else {
                login()
            }
        } catch (e: Exception) {
            // Refresh 실패 시 새로 로그인 시도하여 가용성 유지
            login()
        }
    }

    private fun login(): TokenState {
        val response = portoneClient.login(PortoneTokenRequest(portoneProperties.apiSecret ?: error("external.portone.api-secret is required when enabled=true")))
        return createState(response, null)
    }

    private fun createState(response: PortoneTokenResponse, oldRefreshToken: String?): TokenState {
        return TokenState(
            accessToken = response.accessToken,
            // 응답에 새 refreshToken이 있으면 사용, 없으면 기존 것 유지
            refreshToken = response.refreshToken ?: oldRefreshToken,
            // PortOne V2 Access Token 기본 유효기간은 30분
            expiryTime = Instant.now().plusSeconds(1800)
        )
    }
}
