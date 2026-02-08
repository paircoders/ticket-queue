package com.ticketqueue.common.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * outbox.cleanup.enabled=true 설정이 있을 때만 스케줄링 활성화
 * @Scheduled 어노테이션 동작을 위해 @EnableScheduling 필요
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "outbox.cleanup", name = ["enabled"], havingValue = "true")
class OutboxSchedulingConfig
