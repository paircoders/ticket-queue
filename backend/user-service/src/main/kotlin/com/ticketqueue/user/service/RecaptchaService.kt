package com.ticketqueue.user.service

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.exception.ExternalSystemException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import org.springframework.web.util.UriComponentsBuilder

@Service
class RecaptchaService(
    restClientBuilder: RestClient.Builder,
    @Value("\${recaptcha.url}") private val recaptchaUrl: String,
    @Value("\${recaptcha.secret}") private val recaptchaSecret: String
) {
    private val logger = KotlinLogging.logger {}
    private val restClient = restClientBuilder.build()

    @CircuitBreaker(name = "recaptcha", fallbackMethod = "verifyFallback")
    fun verify(token: String): Boolean {
        val uri = UriComponentsBuilder.fromUriString(recaptchaUrl)
            .queryParam("secret", recaptchaSecret)
            .queryParam("response", token)
            .build()
            .toUri()

        return try {
            val response = restClient.post()
                .uri(uri)
                .retrieve()
                .body<RecaptchaResponse>()

            response?.success ?: false  // 검증 실패(false)는 정상 응답 — CB 카운트 안 됨
        } catch (e: HttpServerErrorException) {
            throw ExternalSystemException(ErrorCode.RECAPTCHA_SERVICE_ERROR, cause = e)  // 5xx
        } catch (e: ResourceAccessException) {
            throw ExternalSystemException(ErrorCode.RECAPTCHA_SERVICE_ERROR, cause = e)  // 네트워크
        } catch (e: Exception) {
            throw ExternalSystemException(ErrorCode.RECAPTCHA_SERVICE_ERROR, cause = e)  // 기타 시스템
        }
    }

    private fun verifyFallback(token: String, ex: Throwable): Boolean {
        if (ex is ExternalSystemException) throw ex
        // CallNotPermittedException (CB OPEN) 포함 — 모든 시스템 장애를 일관 처리
        logger.error(ex) { "reCAPTCHA circuit breaker triggered: ${ex.javaClass.simpleName}" }
        throw ExternalSystemException(ErrorCode.RECAPTCHA_SERVICE_ERROR, cause = ex)
    }

    internal data class RecaptchaResponse(
        val success: Boolean,
        val challengeTs: String? = null,
        val hostname: String? = null,
        val score: Float? = null,
        val action: String? = null,
        val errorCodes: List<String>? = null
    )
}
