package com.ticketqueue.common.config

import com.ticketqueue.common.external.portone.PortoneProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(PortoneProperties::class)
class PortoneConfig
