package com.ticketqueue.common.config

import com.ticketqueue.common.security.InternalApiAuthInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class InternalApiWebConfig(
    private val internalApiAuthInterceptor: InternalApiAuthInterceptor
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(internalApiAuthInterceptor)
            .addPathPatterns("/internal/**")
    }
}
