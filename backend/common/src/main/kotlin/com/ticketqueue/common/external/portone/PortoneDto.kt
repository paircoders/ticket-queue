package com.ticketqueue.common.external.portone

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.OffsetDateTime

/**
 * PortOne V2 API 인증을 위한 토큰 요청 DTO
 */
data class PortoneTokenRequest(
    val apiSecret: String
)

/**
 * PortOne V2 API 인증 결과 응답 DTO
 */
data class PortoneTokenResponse(
    val accessToken: String,
    val refreshToken: String? = null
)

/**
 * PortOne V2 API 토큰 갱신 요청 DTO
 */
data class PortoneRefreshRequest(
    val refreshToken: String
)

/**
 * PortOne V2 본인인증 상세 정보 응답 DTO
 */
data class PortoneIdentityV2Response(
    val id: String,
    val status: String, // READY, VERIFIED, FAILED
    val channel: ChannelDetail? = null,
    val verifiedCustomer: VerifiedCustomerDetail? = null,
    val customData: String? = null,
    val requestedAt: OffsetDateTime? = null,
    val updatedAt: OffsetDateTime? = null,
    val statusChangedAt: OffsetDateTime? = null,
    val verifiedAt: OffsetDateTime? = null,
    val pgTxId: String? = null,
    val pgRawResponse: String? = null,
    val version: String? = null,
    val failure: FailureDetail? = null
) {
    data class ChannelDetail(
        val type: String,
        val id: String,
        val key: String? = null,
        val name: String,
        val pgProvider: String,
        val pgMerchantId: String
    )

    data class VerifiedCustomerDetail(
        val id: String? = null,
        val name: String,
        val operator: String? = null,
        val phoneNumber: String? = null,
        val ci: String? = null,
        val di: String? = null
    )

    data class FailureDetail(
        val code: String? = null,
        val message: String? = null
    )
}