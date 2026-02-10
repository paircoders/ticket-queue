package com.ticketqueue.common.outbox

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Outbox Poller 설정 프로퍼티
 *
 * **외부화 근거:**
 * - 운영 환경별 튜닝 가능 (트래픽 급증 시 batchSize 증가 등)
 * - 코드 재배포 없이 application.yml 또는 환경변수로 변경
 * - Twelve-Factor App 원칙: "설정은 환경에 저장"
 *
 * @property enabled 폴러 활성화 여부 (Producer 서비스만 true)
 * @property maxRetryCount 최대 재시도 횟수 (초과 시 DLQ 이동)
 * @property batchSize 1회 폴링 시 최대 조회 건수
 * @property fixedDelay 폴링 주기 (밀리초)
 */
@ConfigurationProperties(prefix = "outbox.poller")
data class OutboxPollerProperties(
    val enabled: Boolean = false,
    val maxRetryCount: Int = 3,
    val batchSize: Int = 100,
    val fixedDelay: Long = 1000
)
