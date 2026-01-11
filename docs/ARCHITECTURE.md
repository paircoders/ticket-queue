# 📐 아키텍처 설계서

## 문서 정보

| 항목 | 내용 |
|------|------|
| 문서명 | 콘서트 티켓팅 시스템 아키텍처 설계서 |
| 버전 | 1.0.0 |
| 작성일 | 2026-01-11 |
| 최종 수정일 | 2026-01-11 |
| 상태 | Draft |

### 문서 개정 이력

| 버전 | 날짜 | 작성자 | 변경 내용 |
|------|------|--------|----------|
| 1.0.0 | 2026-01-11 | 개발팀 | 초안 작성 |

---

## 목차 (Table of Contents)

이 문서는 유지보수의 용이성을 위해 여러 개의 하위 파일로 분할되었습니다. 각 섹션에 대한 상세 내용은 아래 링크를 참조하십시오.

### 1. 개요 및 원칙
*   [00. 문서 및 시스템 개요](./architecture/00_overview.md)
    *   문서 개요 (목적, 대상, 범위)
    *   시스템 개요 (비즈니스 목적, 주요 기능, 비기능 요구사항)
*   [01. 아키텍처 패턴 및 설계 원칙](./architecture/01_principles.md)
    *   MSA 채택 이유
    *   DDD 적용
    *   이벤트 기반 아키텍처 (EDA)
    *   SAGA 패턴
    *   Transactional Outbox 패턴
    *   Circuit Breaker 패턴

### 2. 시스템 구성
*   [02. 마이크로서비스 구성](./architecture/02_services.md)
    *   서비스 맵 다이어그램
    *   각 서비스별 책임과 경계 (Gateway, User, Event, Queue, Reservation, Payment)
    *   서비스 간 의존성 및 통신
*   [03. 인프라 및 배포](./architecture/03_infrastructure.md)
    *   AWS 클라우드 인프라 구성 (무료티어 최적화)
    *   로컬 개발 환경 (LocalStack)
    *   프론트엔드 인프라 (Next.js, Vercel)
    *   배포 및 CI/CD 파이프라인

### 3. 데이터 및 메시징
*   [04. 데이터 아키텍처](./architecture/04_data.md)
    *   데이터베이스 전략 (PostgreSQL)
    *   ERD 설계
    *   Redis 아키텍처 및 캐싱 전략
    *   데이터 일관성 전략
*   [05. 메시징 아키텍처 (Kafka)](./architecture/05_messaging.md)
    *   Kafka 클러스터 구성
    *   Topic 설계 및 Event Schema
    *   DLQ 처리 및 멱등성 보장

### 4. 상세 설계 및 운영
*   [06. API 및 보안 설계](./architecture/06_api_security.md)
    *   API Gateway 라우팅
    *   주요 API 엔드포인트
    *   보안 아키텍처 (인증/인가, 암호화)
*   [07. 도메인 로직 상세 설계](./architecture/07_domain_logic.md)
    *   대기열 시스템 설계
    *   좌석 예매 시스템 설계
    *   결제 시스템 설계
*   [08. 운영 및 유지보수](./architecture/08_operations.md)
    *   모니터링 및 로깅
    *   성능 최적화 전략
    *   테스트 전략
    *   운영 계획 (SLA, 장애 대응)

### 5. 기타
*   [09. 부록 및 기타](./architecture/09_appendices.md)
    *   기술 부채 및 개선 사항
    *   용어 정의 (Glossary)
    *   아키텍처 결정 기록 (ADR)