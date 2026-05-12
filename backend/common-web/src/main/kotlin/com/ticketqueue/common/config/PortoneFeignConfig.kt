package com.ticketqueue.common.config

import com.ticketqueue.common.external.portone.PortoneFeignClient
import feign.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean

class PortoneFeignConfig {

    @Bean
    fun portoneLogger(): Logger = object : Logger() {
        private val log = LoggerFactory.getLogger(PortoneFeignClient::class.java)

        override fun log(configKey: String, format: String, vararg args: Any) {
            log.info("[PortOne] {}", String.format(format, *args).trim())
        }
    }
}
