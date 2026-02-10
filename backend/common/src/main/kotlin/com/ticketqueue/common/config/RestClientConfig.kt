package com.ticketqueue.common.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

/**
 * 외부 API 통신을 위한 공통 RestClient 설정
 */
@Configuration
class RestClientConfig {

    /**
     * 모든 마이크로서비스에서 공통으로 사용할 RestClient.Builder 빈 생성
     * 
     * [ConditionalOnMissingBean] 설정 이유:
     * 각 서비스(예: payment-service)에서 특정 목적(긴 타임아웃, 추가 인터셉터 등)을 위해 
     * 자체적으로 RestClient.Builder를 빈으로 등록하면, 이 공통 빈은 등록되지 않고 
     * 서비스에서 정의한 빈이 우선권을 갖게 됨 (Overriding 허용)
     */
    @Bean
    @ConditionalOnMissingBean
    fun restClientBuilder(): RestClient.Builder {
        val requestFactory = SimpleClientHttpRequestFactory()
        
        // 기본 타임아웃 설정 (필요 시 각 서비스에서 덮어쓰기 가능)
        requestFactory.setReadTimeout(5000)
        requestFactory.setConnectTimeout(2000)

        return RestClient.builder()
            .requestFactory(requestFactory)
            // 외부 API 호출 시 요청/응답 로그를 남기는 인터셉터 추가
            .requestInterceptor(ExternalApiLoggingInterceptor())
    }
}
