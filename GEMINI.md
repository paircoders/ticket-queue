# Ticket Queue - 프로젝트 컨텍스트

## 1. 프로젝트 개요
**Ticket Queue**는 대규모 사용자 접속(예: K-pop 콘서트 예매)을 처리하기 위해 설계된 대용량 트래픽 콘서트 티켓팅 시스템입니다. 마이크로서비스 아키텍처(MSA)를 사용하여 확장성, 안정성 및 성능을 보장합니다.

*   **주요 목표:** 공정하고 효율적인 대기열 시스템을 통해 높은 동시성을 가진 티켓 판매 관리.
*   **현재 상태:** 설계 및 문서화 단계 (Draft 1.0.0).

## 2. 아키텍처 및 서비스
시스템은 API Gateway를 통해 오케스트레이션되는 도메인별 마이크로서비스로 나뉩니다.

### 핵심 서비스
*   **API Gateway (Spring Cloud Gateway):** 단일 진입점, 라우팅, JWT 검증, 속도 제한(Rate Limiting).
*   **User Service:** 인증(JWT), 회원 관리, OAuth2.
*   **Event Service:** 콘서트, 공연장, 좌석 관리, 캐싱(Redis).
*   **Queue Service:** Redis Sorted Sets를 사용하여 트래픽 폭주를 관리하는 가상 대기실.
*   **Reservation Service:** 좌석 임시 선점(분산 락), 예매 관리.
*   **Payment Service:** 결제 처리(PortOne 연동), SAGA 패턴 구현.

### 인프라 및 기술 스택
*   **Backend:** Java, Spring Boot, Spring Cloud.
*   **Frontend:** Next.js (Vercel).
*   **Database:** PostgreSQL (RDBMS), Redis (Caching & Queue).
*   **Messaging:** Apache Kafka (Event-driven architecture).
*   **Cloud/DevOps:** AWS (ECS/EKS 타겟), LocalStack (로컬 개발), Docker.

## 3. 문서 구조
`docs/` 디렉토리는 모든 요구사항과 아키텍처 결정사항의 기준(Source of Truth)이 됩니다.

*   **`docs/ARCHITECTURE.md`**: 아키텍처 원칙에 대한 주요 진입점.
*   **`docs/REQUIREMENTS.md`**: 상세 기능 및 비기능 요구사항 (121개 항목).
*   **`docs/architecture/`**:
    *   `02_services.md`: 서비스 경계 및 의존성.
    *   `04_data.md`: ERD, 데이터베이스 및 캐싱 전략.
    *   `05_messaging.md`: Kafka 토픽 및 이벤트 스키마.
    *   `06_api_security.md`: API Gateway 및 보안 명세.

## 4. 개발 가이드라인 (계획됨)
*   **Code Style:** Java 표준 컨벤션 (Google Style Guide 권장).
*   **Branching:** Gitflow 또는 Trunk-based development (TBD).
*   **Commit Messages:** Conventional Commits.
*   **Testing:**
    *   Unit Tests: JUnit 5, Mockito.
    *   Integration Tests: TestContainers.
    *   Load Testing: k6 (대기열 및 예매에 집중).

## 5. 시작하기
현재 프로젝트는 **설계 단계(Design Phase)**에 있으므로, 코드를 작성하기 전에 요구사항을 이해하는 데 집중하십시오.

1.  `docs/REQUIREMENTS.md`를 **읽고** 구체적인 기능 세트를 이해합니다.
2.  `docs/architecture/02_services.md`를 **검토하여** 서비스 상호 작용을 이해합니다.
3.  `docs/architecture/04_data.md`에서 데이터 모델을 **확인**합니다.

*참고: 빌드 스크립트 및 소스 코드 디렉토리는 향후 단계에서 추가될 예정입니다.*