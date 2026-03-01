package com.ticketqueue.common.config

import com.ticketqueue.common.external.portone.PortoneProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(prefix = "external.portone", name = ["api-secret"])
@EnableConfigurationProperties(PortoneProperties::class)
class PortoneConfig
