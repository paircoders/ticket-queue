# 📐 아키텍처 설계서 개요

## 1. 문서 개요

### 1.1 문서 목적

본 문서는 **콘서트/공연 티켓팅 시스템**의 아키텍처 설계를 정의하고, 시스템의 구조, 컴포넌트 간 상호작용, 기술 스택, 그리고 설계 결정 사항을 문서화

**주요 목적:**
- 시스템 구조를 명확히 이해하고 일관된 방향으로 개발할 수 있도록 지침 제공
- 마이크로서비스 간 책임과 경계를 명확히 정의하여 개발 충돌 최소화
- 기술적 의사결정의 근거를 문서화하여 향후 유지보수 및 확장 용이성 확보
- 요구사항 명세서([REQUIREMENTS.md](../REQUIREMENTS.md))의 요구사항을 아키텍처로 구현하는 방법 제시

### 1.2 문서 범위
본 문서는 다음을 포함합니다:
- MSA(Microservices Architecture) 기반 시스템 아키텍처
- 6개 마이크로서비스의 구성 및 상호작용
- AWS 클라우드 인프라 구성 (무료티어 최적화)
- LocalStack 기반 로컬 개발 환경
- 데이터베이스, 캐시, 메시징 시스템 설계
- 보안, 성능, 모니터링 전략

본 문서는 다음을 포함하지 않습니다:
- 프론트엔드 상세 설계 (Next.js 구조는 개요만 포함)
- 상세 API 스펙 (Swagger/OpenAPI 문서로 별도 관리)

### 1.3 아키텍처 설계 원칙

1. **확장성 (Scalability)**
   - 마이크로서비스 아키텍처로 서비스별 독립적 확장 가능
   - Redis 기반 대기열로 대규모 동시 접속 처리 (50,000명)

2. **가용성 (Availability)**
   - Circuit Breaker 패턴으로 장애 전파 방지
   - Redis/RDB 이중화 및 자동 백업
   - 목표: 99.9% 가용성

3. **성능 (Performance)**
   - Redis 캐싱으로 조회 성능 최적화
   - P95 응답 시간 목표 (대기열 진입 < 100ms, 조회 < 50ms)
   - 비동기 이벤트 기반 아키텍처로 응답 시간 단축

4. **보안 (Security)**
   - JWT 기반 인증/인가
   - reCAPTCHA로 봇/매크로 차단
   - AES-256 암호화로 개인정보 보호
   - 본인인증(CI/DI)으로 1인 1계정 강제

5. **데이터 일관성 (Data Consistency)**
   - SAGA 패턴으로 분산 트랜잭션 관리
   - Transactional Outbox 패턴으로 이벤트 발행 신뢰성 보장
   - Redisson 분산 락으로 좌석 선점 동시성 제어

6. **비용 효율성 최적화 (Cost Efficiency)**
   - **인프라 간소화**: 복잡한 VPC/Subnet 대신 Public Subnet + Security Group으로 보안 관리
   - **Docker Compose 통합**: 로컬과 운영 환경을 Docker Compose로 통일하여 배포/운영 복잡도 제거
   - **EC2 단일 인스턴스**: 모든 서비스(App, DB, Kafka, Redis)를 단일 EC2 내 컨테이너로 실행 (비용 최소화)

7. **개발 생산성 (Developer Productivity)**
   - **인프라보다 로직 집중**: Kafka, 동시성 제어 등 핵심 백엔드 기술 구현에 집중
   - **단순한 배포**: `docker-compose up` 명령 하나로 배포 완료
   - **명확한 서비스 경계**: MSA 구조는 유지하되 운영 오버헤드 제거

### 1.4 기술 스택 개요

| 계층 | 기술 | 용도 |
|------|------|------|
| **Frontend** | Next.js 16+ | React 기반 SSR/CSR |
|  | Vercel | 프론트엔드 배포 플랫폼 |
| **API Gateway** | Spring Cloud Gateway | 통합 진입점, 라우팅, 인증 |
| **Backend** | spring boot 4.0.2 3.5.10 | 마이크로서비스 프레임워크 |
|  | Java 25+ | 백엔드 언어 |
| **Database** | PostgreSQL 18 | RDB (단일 인스턴스, 스키마 분리) |
|  | Redis 7.x | 캐시, 대기열, 분산 락 |
| **Messaging** | Apache Kafka 4.1.1 | 이벤트 스트리밍 |
| **Security** | Spring Security | 인증/인가 프레임워크 |
|  | JWT | 토큰 기반 인증 |
|  | reCAPTCHA | 봇 차단 |
| **Resilience** | Resilience4j | Circuit Breaker, Rate Limiter |
| **Infrastructure** | AWS (RDS, ElastiCache, EC2, ALB) | 클라우드 인프라 |
|  | LocalStack | 로컬 AWS 에뮬레이션 |
|  | Docker / Docker Compose | 컨테이너 |
| **CI/CD** | GitHub Actions | 빌드, 테스트, 배포 자동화 |
| **Monitoring** | AWS CloudWatch | 로그, 메트릭, 알람 |
|  | Spring Cloud Sleuth | 분산 추적 |

---

## 2. 시스템 개요

### 2.1 비즈니스 목적 및 배경

**비즈니스 목적:**
대규모 동시 접속이 발생하는 콘서트/공연 티켓 예매 시스템을 안정적으로 운영하며, 공정한 예매 기회를 제공하고 부정 예매(매크로, 중복 가입 등)를 차단합니다.

**핵심 비즈니스 가치:**
- **공정성**: 대기열 시스템으로 선착순 공정성 확보
- **보안성**: 본인인증(CI/DI), CAPTCHA로 1인 1계정 강제 및 봇 차단
- **안정성**: 대규모 트래픽 대응 (공연당 50,000명 대기열, 36,000명/시간 처리)
- **신뢰성**: SAGA 패턴으로 결제-예매 데이터 일관성 보장

**배경:**
- 기존 티켓팅 시스템의 과부하 및 불공정 예매 문제 해결 필요
- MSA 아키텍처로 서비스별 독립 확장 및 장애 격리
- 이벤트 기반 아키텍처로 느슨한 결합 및 확장성 확보

### 2.2 주요 기능 요약

#### 2.2.1 인증 및 회원 관리 (User Service)
- **회원가입**: 약관동의 → CAPTCHA → 본인인증(CI/DI) → 정보입력
- **로그인**: 이메일/비밀번호 + CAPTCHA, JWT 토큰 발급
- **소셜 로그인**: 카카오, 네이버, 구글 OAuth2 지원 (선택)
- **프로필 관리**: 조회, 수정, 비밀번호 변경, 회원 탈퇴

**관련 요구사항**: REQ-AUTH-001 ~ REQ-AUTH-021 (20개)

#### 2.2.2 공연 관리 (Event Service)
- **공연 CRUD**: 생성, 수정, 삭제 (관리자)
- **공연 조회**: 목록 조회 (페이징, 필터링, 검색), 상세 조회
- **좌석 정보 조회**: 등급별(VIP/S/A/B) 좌석 그룹핑 및 가격 조회
- **공연장/홀 관리**: 공연장 및 홀 정보 CRUD
- **좌석 상태 관리**: Kafka 이벤트 수신하여 좌석 상태 업데이트

**관련 요구사항**: REQ-EVT-001 ~ REQ-EVT-024 (24개)

#### 2.2.3 대기열 (Queue Service)
- **대기열 진입**: Redis Sorted Set 기반, 공연별 최대 50,000명
- **대기열 상태 조회**: REST 폴링(5초 권장), 현재 순서 및 예상 대기 시간
- **배치 승인**: 1초마다 10명씩 승인 (Lua 스크립트 원자성 보장)
- **Queue Token 발급**: Reservation Token (qr_xxx) 단일 모델
- **대기열 만료**: Token TTL 10분, 자동 제거

**관련 요구사항**: REQ-QUEUE-001 ~ REQ-QUEUE-011 (11개)

#### 2.2.4 예매 (Reservation Service)
- **좌석 선점**: Redisson 분산 락, Redis 5분 TTL
- **좌석 변경**: 기존 락 해제 → 신규 락 획득
- **예매 확정**: 결제 성공 이벤트 수신 시 PENDING → CONFIRMED
- **예매 취소**: 사용자 취소 (공연 당일 불가), 보상 트랜잭션 (결제 실패 시)
- **나의 예매 내역**: 사용자별 예매 목록 조회

**관련 요구사항**: REQ-RSV-001 ~ REQ-RSV-012 (12개)

#### 2.2.5 결제 (Payment Service)
- **결제 프로세스**: PortOne 연동, 신용카드 결제
- **결제 상태 관리**: PENDING / SUCCESS / FAILED / REFUNDED
- **멱등성 보장**: paymentKey 기반 중복 방지
- **SAGA 패턴**: 결제 성공 → 예매 확정, 결제 실패 → 예매 취소 (보상 트랜잭션)
- **Kafka 이벤트 발행**: payment.success, payment.failed

**관련 요구사항**: REQ-PAY-001 ~ REQ-PAY-015 (15개)

#### 2.2.6 API Gateway
- **동적 라우팅**: Path 기반 5개 서비스 라우팅
- **JWT 토큰 검증**: 만료/변조 확인, 사용자 정보 전달
- **Rate Limiting**: IP 기반, 사용자 기반 제한
- **Circuit Breaker**: 다운스트림 서비스 장애 격리
- **CORS, 보안 헤더**: 프론트엔드 통신 지원

**관련 요구사항**: REQ-GW-001 ~ REQ-GW-018 (18개)

### 2.3 비기능 요구사항 요약

#### 2.3.1 성능 요구사항
| 항목 | 목표 | 관련 요구사항 |
|------|------|---------------|
| 대기열 진입 | P95 < 100ms | REQ-QUEUE-015 |
| 대기열 조회 | P95 < 50ms | REQ-QUEUE-015 |
| 공연 목록 조회 | P95 < 200ms | REQ-EVT-004 |
| 공연 상세 조회 | P95 < 100ms | REQ-EVT-005 |
| 좌석 정보 조회 | P95 < 300ms | REQ-EVT-006 |
| 대기열 처리량 | 36,000명/시간 (10명/초) | REQ-QUEUE-005 |
| 대기열 용량 | 공연당 최대 50,000명 | REQ-QUEUE-010 |

#### 2.3.2 보안 요구사항
- **인증**: JWT Access/Refresh Token (1시간/7일)
- **암호화**: 비밀번호 BCrypt, 개인정보 AES-256 (선택)
- **본인인증**: PortOne CI/DI로 1인 1계정 강제
- **봇 차단**: reCAPTCHA (회원가입, 로그인)
- **토큰 블랙리스트**: Redis 기반, TTL 관리

#### 2.3.3 가용성 요구사항
- **Redis 가용성**: 99.9%
- **Circuit Breaker**: 실패율 임계값 초과 시 Circuit Open
- **Timeout**: API Gateway 기본 30초, Payment 60초
- **Retry**: GET/PUT/DELETE만 최대 2회 재시도

#### 2.3.4 확장성 요구사항
- **Auto Scaling**: 최소 1, 최대 3 인스턴스
- **서비스별 독립 확장**: MSA 아키텍처
- **Redis Cluster**: 향후 확장 고려 (초기 단일 노드)

### 2.4 시스템 제약사항 및 가정사항

#### 2.4.1 제약사항
1. **리소스 제약**: 개발자 2명 (A개발자, B개발자)
2. **비용 제약**: 월 유지비용 최소화
3. **인프라 전략**: 
   - **복잡성 제거**: AWS VPC, NAT Gateway, EKS 등 운영 리소스가 많이 드는 인프라 제외
   - **Docker 중심**: 모든 컴포넌트(App, MW, DB)를 Docker Container로 실행

#### 2.4.2 가정사항
1. **트래픽 패턴**:
   - 기술적 검증을 위한 가상의 대용량 트래픽 상황 가정 (부하 테스트로 증명)
   - 실제 운영 트래픽이 아닌, 아키텍처의 한계점을 테스트하는 것이 목표

2. **배포 환경**:
   - 로컬/운영 동일하게 **Docker Compose** 사용
   - AWS EC2 (Ubuntu) 환경
   - 프론트엔드: Vercel