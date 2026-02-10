# 📐 아키텍처 설계서

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
    *   Docker Compose 기반 로컬/운영 환경 통합
    *   AWS EC2 단일 인스턴스 배포 전략 (비용 및 운영 최적화)
    *   프론트엔드 인프라 (Next.js, Vercel)
    *   단순화된 배포 파이프라인

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
*   [07. 운영 및 유지보수](./architecture/07_operations.md)
    *   모니터링 및 로깅
    *   성능 최적화 전략
    *   테스트 전략
    *   운영 계획 (SLA, 장애 대응)

### 5. 프론트엔드 설계
*   [00. 프론트엔드 개요](./frontend/00_overview.md)
    *   기술 스택 (Next.js, React Query, Zustand)
    *   설계 원칙 (UX 우선, 접근성, 반응형)
    *   프로젝트 구조 및 환경 변수
*   [01. 페이지 구조 및 라우팅](./frontend/01_pages.md)
    *   전체 페이지 목록 및 App Router 구조
    *   라우트 그룹별 레이아웃 계층
    *   미들웨어 (인증 체크, 리디렉트)
*   [02. 컴포넌트 설계](./frontend/02_components.md)
    *   공통 UI 컴포넌트 (Button, Input, Modal 등)
    *   페이지별 주요 컴포넌트 트리
    *   디자인 시스템 기초
*   [03. 상태 관리 및 데이터 흐름](./frontend/03_state_data.md)
    *   Server State (React Query), Client State (Zustand)
    *   API 호출 패턴 및 에러 처리
*   [04. 인증/인가 흐름](./frontend/04_auth_security.md)
    *   JWT 토큰 저장 전략 (httpOnly 쿠키)
    *   로그인/회원가입 화면 흐름
    *   reCAPTCHA 및 PortOne 본인인증 연동
*   [05. 대기열 UX 설계](./frontend/05_queue_ux.md)
    *   대기열 진입 → 대기 → 승인 화면 전환
    *   폴링 전략 (5초 간격 REST 폴링)
    *   타이머 UI (10분 TTL 시각화)
*   [06. 결제 UX 설계](./frontend/06_payment_ux.md)
    *   좌석 선택 → 결제 → 완료 화면 흐름
    *   PortOne SDK 연동 (결제 위젯 통합)
    *   좌석 선점 타이머 (5분 TTL)
*   [07. 성능/SEO 전략](./frontend/07_performance.md)
    *   페이지별 렌더링 전략 (SSR/CSR)
    *   이미지 최적화 (next/image)
    *   Core Web Vitals 목표 및 측정