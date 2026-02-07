package com.ticketqueue.user.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder

@Service
class RecaptchaService(
    @Value("\${recaptcha.url}") private val recaptchaUrl: String,
    @Value("\${recaptcha.secret}") private val recaptchaSecret: String
) {
    private val restClient = RestClient.create()

    fun verify(token: String): Boolean {

        val uri = UriComponentsBuilder.fromUriString(recaptchaUrl)
            .queryParam("secret", recaptchaSecret)
            .queryParam("response", token)
            .build()
            .toUri()

        val response = restClient.post()
            .uri(uri)
            .retrieve()
            .body(RecaptchaResponse::class.java)

        return response?.success ?: false
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
