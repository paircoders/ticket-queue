# Ticket Queue - 프로젝트 컨텍스트

## 1. 프로젝트 개요
**Ticket Queue**는 대규모 사용자 접속(예: K-pop 콘서트 예매)을 처리하기 위해 설계된 대용량 트래픽 콘서트 티켓팅 시스템입니다. 마이크로서비스 아키텍처(MSA)를 사용하여 확장성, 안정성 및 성능을 보장합니다.

*   **주요 목표:** 공정하고 효율적인 대기열 시스템을 통해 높은 동시성을 가진 티켓 판매 관리.
*   **현재 상태:** **설계 및 문서화 단계 (Design Phase)**.
    *   *참고:* 소스 코드 구현은 아직 시작되지 않았습니다. `docs/` 디렉토리가 프로젝트의 유일한 진실 공급원(Source of Truth)입니다.

## 2. 아키텍처 및 서비스
시스템은 API Gateway를 통해 오케스트레이션되는 도메인별 마이크로서비스로 구성됩니다.

### 핵심 서비스
*   **API Gateway (Spring Cloud Gateway):** 단일 진입점, 동적 라우팅, JWT 검증, Rate Limiting, Circuit Breaker.
*   **User Service:** 인증(JWT), 회원 관리, OAuth2(카카오, 네이버, 구글), CI/DI 본인인증.
*   **Event Service:** 콘서트, 공연장, 좌석 정보 관리. Redis 캐싱을 통한 조회 성능 최적화.
*   **Queue Service:** Redis Sorted Sets를 활용한 가상 대기실. 트래픽 폭주 제어 (공연당 최대 5만 명).
*   **Reservation Service:** 좌석 임시 선점(Redisson 분산 락, 5분 TTL), 예매 관리.
*   **Payment Service:** 결제 처리(PortOne 연동), SAGA 패턴을 통한 분산 트랜잭션 관리.

### 인프라 및 기술 스택
*   **Frontend:** Next.js 16+ (Vercel), React.
*   **Backend:** Java 25, spring boot 4.0.2 3.5.10, Spring Cloud.
*   **Database:** PostgreSQL 18 (단일 인스턴스, 서비스별 스키마 분리), Redis 7.x (캐시, 대기열, 락).
*   **Messaging:** Apache Kafka 4.x (이벤트 기반 아키텍처).
*   **Cloud/DevOps:** AWS (Free Tier 최적화: RDS t3.micro, EC2 t2.micro), LocalStack (로컬 개발), Docker Compose.

## 3. 주요 설계 결정 및 제약사항
*   **AWS Free Tier 최적화:** 비용 효율성을 위해 단일 RDS 인스턴스 내 논리적 DB 분리 등 무료 티어 한도 내에서 아키텍처가 설계되었습니다.
*   **동시성 제어:**
    *   **대기열:** Redis Sorted Set으로 순위 계산 및 진입 제어.
    *   **좌석 선점:** Redisson 분산 락으로 원자적 좌석 선점 보장.
*   **데이터 일관성:** SAGA 패턴(Choreography)을 통해 결제와 예매 간의 데이터 일관성 보장.
*   **보안:** JWT Access/Refresh Token, reCAPTCHA v2(봇 차단).

## 4. 문서 구조
모든 요구사항과 아키텍처 결정사항은 `docs/` 디렉토리에 정의되어 있습니다.

*   **`docs/REQUIREMENTS.md`**: 상세 기능 및 비기능 요구사항 (121개 항목).
*   **`docs/ARCHITECTURE.md`**: 아키텍처 원칙 및 설계 개요.
*   **`docs/architecture/`**: 상세 아키텍처 설계 (Overview, Principles, Services, Infrastructure, Data, Kafka, Security, Operations).
*   **`docs/specification/`**: 각 서비스별 상세 API 및 도메인 명세 (User, Event, Queue, Reservation, Payment).

## 5. 시작하기
현재 프로젝트는 **설계 단계**에 있으므로, 구현 전에 아키텍처를 이해하는 것이 중요합니다.

1.  `docs/REQUIREMENTS.md`를 **정독**하여 상세 기능 명세를 파악합니다.
2.  `docs/architecture/00_overview.md`를 통해 전체 시스템의 기술적 범위와 제약을 이해합니다.
3.  `docs/specification/00_overview.md` 및 각 서비스 명세를 통해 상세 구현 요구사항을 확인합니다.

*향후 계획: Docker Compose 기반의 로컬 인프라 구축 및 백엔드 서비스 스캐폴딩이 진행될 예정입니다.*
