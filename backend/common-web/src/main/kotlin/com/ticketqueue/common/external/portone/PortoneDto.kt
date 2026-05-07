package com.ticketqueue.common.external.portone

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

/** 결제 사전 등록 요청 */
data class PortonePreRegisterRequest(
    val storeId: String,
    val totalAmount: Long,
    val taxFreeAmount: Long? = null,
    val currency: String? = null
)

/** 결제 단건 조회 응답 */
data class PortonePaymentResponse(
    val id: String,
    val transactionId: String,
    val merchantId: String,
    val storeId: String,
    val status: String, // READY, PENDING, PAID, VIRTUAL_ACCOUNT_ISSUED, PARTIALLY_CANCELLED, CANCELLED, FAILED
    val amount: PortonePaymentAmount,
    val currency: String,
    val channel: PortoneSelectedChannel,
    val version: String,
    val requestedAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val statusChangedAt: OffsetDateTime,
    val orderName: String,
    val customer: PortoneCustomer,
    val method: Map<String, Any?>? = null,
    val paidAt: OffsetDateTime? = null,
    val failure: PortonePaymentFailure? = null,
    val pgTxId: String? = null
)

/** 결제 금액 상세 */
data class PortonePaymentAmount(
    val total: Long,
    val taxFree: Long,
    val vat: Long? = null,
    val supply: Long? = null,
    val discount: Long,
    val paid: Long,
    val cancelled: Long,
    val cancelledTaxFree: Long
)

/** 결제 실패 상세 */
data class PortonePaymentFailure(
    val reason: String? = null,
    val pgCode: String? = null,
    val pgMessage: String? = null
)

/** 선택된 채널 정보 */
data class PortoneSelectedChannel(
    val type: String, // LIVE, TEST
    val pgProvider: String,
    val pgMerchantId: String,
    val id: String? = null,
    val key: String? = null,
    val name: String? = null
)

/** 고객 정보 */
data class PortoneCustomer(
    val id: String? = null,
    val name: String? = null,
    val birthYear: String? = null,
    val gender: String? = null,
    val email: String? = null,
    val phoneNumber: String? = null,
    val zipcode: String? = null
)