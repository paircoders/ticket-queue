package com.ticketqueue.payment.config

import io.netty.channel.ChannelOption
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration

@Configuration
class WebClientConfig(
    @Value("\${portone.base-url}")
    private val portoneBaseUrl: String,

    @Value("\${portone.timeout}")
    private val timeout: Long
) {

    @Bean
    fun portoneWebClient(): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout.toInt())
            .responseTimeout(Duration.ofMillis(timeout))

        return WebClient.builder()
            .baseUrl(portoneBaseUrl)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}
