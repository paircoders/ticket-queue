package com.ticketqueue.gateway.config

import org.springframework.context.annotation.Configuration

/**
 * Spring Cloud Gateway 기본 설정
 *
 * 현재는 application.yml 기반 라우트 설정만 사용하며,
 * 향후 Issue #13~#19에서 필요한 Bean 추가 예정:
 * - Issue #13: 동적 라우팅 설정
 * - Issue #14: JWT 인증 필터
 * - Issue #15: Queue Token 헤더 전달
 * - Issue #16: Circuit Breaker Fallback
 * - Issue #17: CORS 및 보안 헤더
 */
@Configuration
class GatewayConfig
