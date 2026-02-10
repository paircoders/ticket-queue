package com.ticketqueue.user.service

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.user.exception.UserException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import org.springframework.web.util.UriComponentsBuilder

@Service
class RecaptchaService(
    restClientBuilder: RestClient.Builder,
    @Value("\${recaptcha.url}") private val recaptchaUrl: String,
    @Value("\${recaptcha.secret}") private val recaptchaSecret: String
) {
    private val restClient = restClientBuilder.build()

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

            response?.success ?: false
        } catch (e: Exception) {
            throw UserException(ErrorCode.RECAPTCHA_FAILED)
        }
    }

    private data class RecaptchaResponse(
        val success: Boolean,
        val challengeTs: String? = null,
        val hostname: String? = null,
        val score: Float? = null,
        val action: String? = null,
        val errorCodes: List<String>? = null
    )
}
