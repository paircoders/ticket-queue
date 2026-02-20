package com.ticketqueue.common.external.portone

import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.*

/**
 * PortOne V2 API 연동을 위한 Feign Client
 */
@FeignClient(name = "portone-v2-client", url = "\${external.portone.api-url:https://api.portone.io}")
interface PortoneFeignClient {

    /**
     * V2 Access Token 발급
     */
    @PostMapping("/login/api-secret")
    fun login(@RequestBody request: PortoneTokenRequest): PortoneTokenResponse

    /**
     * V2 Access Token 갱신
     */
    @PostMapping("/token/refresh")
    fun refreshToken(@RequestBody request: PortoneRefreshRequest): PortoneTokenResponse

    /**
     * 본인인증 정보 상세 조회 (V2)
     * @param identityVerificationId 인증 고유 ID
     * @param token Authorization: Bearer {ACCESS_TOKEN}
     */
    @GetMapping("/identity-verifications/{identityVerificationId}")
    fun getIdentityVerification(
        @PathVariable("identityVerificationId") identityVerificationId: String,
        @RequestHeader("Authorization") token: String
    ): PortoneIdentityV2Response
}
