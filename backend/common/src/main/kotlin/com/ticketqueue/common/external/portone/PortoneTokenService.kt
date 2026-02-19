package com.ticketqueue.common.external.portone

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * PortOne V2 Access Token 관리 및 자동 갱신 서비스
 */
@Service
class PortoneTokenService(
    private val portoneClient: PortoneFeignClient,
    @Value("\${external.portone.api-secret}") private val apiSecret: String
) {
    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var expiryTime: LocalDateTime? = null

    /**
     * 유효한 Access Token을 반환 (필요 시 자동 갱신)
     * @return Bearer {ACCESS_TOKEN}
     */
    @Synchronized
    fun getAccessToken(): String {
        if (shouldRefresh()) {
            refreshOrLogin()
        }
        return "Bearer $accessToken"
    }

    private fun shouldRefresh(): Boolean {
        return accessToken == null || expiryTime == null || LocalDateTime.now().isAfter(expiryTime!!.minusMinutes(2))
    }

    private fun refreshOrLogin() {
        try {
            if (refreshToken != null) {
                val response = portoneClient.refreshToken(PortoneRefreshRequest(refreshToken!!))
                accessToken = response.accessToken
                // PortOne V2 Access Token은 보통 30분 유효
                expiryTime = LocalDateTime.now().plusMinutes(30)
            } else {
                login()
            }
        } catch (e: Exception) {
            // Refresh 실패 시 새로 로그인 시도
            login()
        }
    }

    private fun login() {
        val response = portoneClient.login(PortoneTokenRequest(apiSecret))
        accessToken = response.accessToken
        refreshToken = response.refreshToken
        expiryTime = LocalDateTime.now().plusMinutes(30)
    }
}
