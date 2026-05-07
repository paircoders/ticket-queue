package com.ticketqueue.common.config

import com.fasterxml.jackson.databind.ObjectMapper
import feign.Logger
import feign.RequestInterceptor
import feign.Retryer
import feign.codec.ErrorDecoder
import org.slf4j.MDC
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnClass(RequestInterceptor::class)
class FeignConfig {

    @Bean
    fun traceIdRequestInterceptor(): RequestInterceptor {
        return RequestInterceptor { template ->
            MDC.get("traceId")?.let { traceId ->
                template.header("X-Trace-Id", traceId)
            }
        }
    }

    @Bean
    fun feignErrorDecoder(objectMapper: ObjectMapper): ErrorDecoder {
        return FeignErrorDecoder(objectMapper)
    }

    // 재시도 정책: 지수 백오프, period=500ms, maxPeriod=2000ms, maxAttempts=3
    @Bean
    fun feignRetryer(): Retryer {
        return Retryer.Default(500, 2000, 3)
    }

    @Bean
    fun feignLoggerLevel(): Logger.Level {
        return Logger.Level.FULL
    }
}
