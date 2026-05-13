package com.ticketqueue.common.external.portone

import com.ticketqueue.common.config.PortoneFeignConfig
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.*

/**
 * PortOne V2 API 연동을 위한 Feign Client
 */
@FeignClient(
    name = "portone-v2-client",
    url = "\${external.portone.api-url:https://api.portone.io}",
    configuration = [PortoneFeignConfig::class]
)
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
     * @param storeId 상점 ID (optional)
     * @param token Authorization: Bearer {ACCESS_TOKEN}
     */
    @GetMapping("/identity-verifications/{identityVerificationId}")
    fun getIdentityVerification(
        @PathVariable("identityVerificationId") identityVerificationId: String,
        @RequestParam("storeId") storeId: String?,
        @RequestHeader("Authorization") token: String
    ): PortoneIdentityV2Response

    /**
     * 결제 사전 등록 (Prepare) — 금액 위변조 방지용 사전 검증 등록
     * @param paymentId 가맹점 결제 ID (paymentKey)
     * @param request 사전 등록 요청 (storeId, totalAmount 등)
     * @param token Authorization: Bearer {ACCESS_TOKEN}
     */
    @PostMapping("/payments/{paymentId}/pre-register")
    fun preRegisterPayment(
        @PathVariable("paymentId") paymentId: String,
        @RequestBody request: PortonePreRegisterRequest,
        @RequestHeader("Authorization") token: String
    )

    /**
     * 결제 단건 조회 — 클라이언트 결제 완료 후 서버에서 결과 검증
     * @param paymentId 가맹점 결제 ID (paymentKey)
     * @param token Authorization: Bearer {ACCESS_TOKEN}
     */
    @GetMapping("/payments/{paymentId}")
    fun getPayment(
        @PathVariable("paymentId") paymentId: String,
        @RequestParam("storeId") storeId: String?,
        @RequestHeader("Authorization") token: String
    ): PortonePaymentResponse
}
