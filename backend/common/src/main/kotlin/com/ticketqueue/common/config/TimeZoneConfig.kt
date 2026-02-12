package com.ticketqueue.common.config

import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Configuration
import java.util.TimeZone

/**
 * JVM 타임존을 UTC로 고정하여 환경에 관계없이 일관된 시간 처리를 보장합니다.
 *
 * - 개발 환경(PC), CI/CD, 프로덕션 환경에서 모두 동일한 타임존 사용
 * - LocalDateTime.now(), @CreationTimestamp 등 모든 JVM 시간 생성이 UTC 기준
 * - 비즈니스 시간(KST)은 필요 시 명시적으로 변환
 */
@Configuration
class TimeZoneConfig {

    @PostConstruct
    fun init() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }
}
