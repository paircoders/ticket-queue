# Coding Standards

> **버전**: 1.0.0 | **최종 수정**: 2026-02-21
>
> 이 문서는 Ticket Queue 프로젝트의 팀 코딩 컨벤션을 정의합니다.
> CodeRabbit PR 리뷰 시 이 규칙을 기준으로 피드백을 제공합니다.

---

## 목차

1. [프로젝트 구조](#1-프로젝트-구조)
2. [Kotlin 코딩 컨벤션](#2-kotlin-코딩-컨벤션)
3. [Spring Boot 패턴](#3-spring-boot-패턴)
4. [JPA Entity 규칙](#4-jpa-entity-규칙)
5. [API 응답 및 에러 처리](#5-api-응답-및-에러-처리)
6. [DTO 및 Validation](#6-dto-및-validation)
7. [Kafka 이벤트](#7-kafka-이벤트)
8. [보안](#8-보안)
9. [로깅](#9-로깅)
10. [테스트](#10-테스트)
11. [Gradle 빌드](#11-gradle-빌드)
12. [데이터베이스](#12-데이터베이스)
13. [Docker](#13-docker)
14. [Git 컨벤션](#14-git-컨벤션)

---

## 1. 프로젝트 구조

### 패키지 명명

최상위 패키지는 `com.ticketqueue.{service}` 형식을 따릅니다.

```
com.ticketqueue.common.*        # 공통 모듈
com.ticketqueue.gateway.*       # API Gateway
com.ticketqueue.user.*          # User Service
com.ticketqueue.event.*         # Event Service
com.ticketqueue.queue.*         # Queue Service
com.ticketqueue.reservation.*   # Reservation Service
com.ticketqueue.payment.*       # Payment Service
```

### 서비스별 레이어 구조

각 서비스는 다음 패키지 구조를 따릅니다:

```
{service}/
├── entity/          # JPA Entity 및 Enum 클래스
├── repository/      # JpaRepository 인터페이스 + Custom/Impl (Querydsl)
├── service/         # 비즈니스 로직
├── controller/      # REST 컨트롤러
├── config/          # 설정 클래스 (@Configuration, @ConfigurationProperties)
├── exception/       # 서비스별 예외 (BusinessException 서브클래스)
├── dto/             # 요청/응답 DTO
└── kafka/           # Kafka Consumer 리스너 (해당 서비스만)
```

### common 모듈 역할

`common` 모듈은 모든 서비스가 공유하는 코드만 포함합니다:

- `exception/` — `BusinessException`, `ErrorCode`, `GlobalExceptionHandler`
- `dto/` — `ErrorResponse`, `PageResponse`
- `event/` — `BaseEvent`, `EventMetadata`, 도메인 이벤트 DTO
- `kafka/` — `KafkaTopicConfig`, `IdempotentConsumerTemplate`, `ExceptionClassifier`, `KafkaProducerConfig`, `KafkaErrorHandlerConfig`
- `outbox/` — Outbox 패턴 관련 (`OutboxEvent`, `OutboxPollerService`, `ProcessedEvent` 등)
- `security/` — `InternalApiAuthInterceptor`, `InternalApiKeyValidator`
- `filter/` — `TraceIdFilter`
- `config/` — `JacksonConfig`, `FeignConfig`, `QuerydslConfig` 등 공통 설정
- `util/` — `DateTimeUtils`, `UuidUtils`, `EncryptionUtils`

---

## 2. Kotlin 코딩 컨벤션

### 명명 규칙

| 대상 | 규칙 | 예시 |
|------|------|------|
| 클래스 | PascalCase | `OutboxPollerService`, `KafkaTopicConfig` |
| 함수/변수 | camelCase | `processedEventService`, `findByAggregateType()` |
| 상수 | UPPER_SNAKE_CASE (companion object 내) | `TRACE_ID_HEADER`, `MAX_TRACE_ID_LENGTH` |
| 패키지 | 소문자 단수 | `com.ticketqueue.event.entity` |
| 파일 | PascalCase, 클래스명과 일치 | `OutboxEvent.kt` |

### 클래스 유형 선택 기준

```kotlin
// DTO: data class 사용 (equals/hashCode/copy 자동 생성)
data class ErrorResponse(val code: String, val message: String, ...)

// Configuration Properties: data class 사용
@ConfigurationProperties(prefix = "outbox.poller")
data class OutboxPollerProperties(val enabled: Boolean = false, ...)

// JPA Entity: class 사용 (data class 금지 — lazy loading 문제)
@Entity
class Event(...)

// Kafka 이벤트 DTO: data class 사용
data class PaymentSuccessEvent(...) : BaseEvent(...)

// 싱글턴 유틸리티: object 사용
object DateTimeUtils { ... }
```

### Enum 패턴 — Rich Enum + companion object

값 조회가 필요한 Enum은 companion object에 미리 계산된 Map을 정의합니다:

```kotlin
enum class KafkaTopicConfig(
    val aggregateType: String,
    val topic: String,
    val dlqTopic: String
) {
    PAYMENT("Payment", "payment.events", "dlq.payment"),
    RESERVATION("Reservation", "reservation.events", "dlq.reservation");

    companion object {
        // O(1) 조회를 위한 미리 계산된 Map
        private val byAggregateType: Map<String, KafkaTopicConfig> =
            entries.associateBy { it.aggregateType }

        fun findByAggregateType(aggregateType: String): KafkaTopicConfig? =
            byAggregateType[aggregateType]
    }
}
```

단순 상태 열거는 companion object 없이 작성합니다:

```kotlin
enum class EventStatus { PREPARING, OPEN, ENDED, CANCELLED }
```

### Scope 함수

`apply`는 객체 초기화, `let`은 null 처리, `also`는 사이드 이펙트에 사용합니다.
`run`/`with`는 가독성이 명확한 경우에만 허용합니다.

### null 안전성

- `!!` 연산자 사용을 지양합니다. 불가피한 경우 주석으로 이유를 명시합니다.
- `?:` Elvis 연산자로 기본값을 처리합니다.
- `takeIf { }` / `takeUnless { }` 를 조건부 null 처리에 활용합니다.

---

## 3. Spring Boot 패턴

### 의존성 주입 — 생성자 주입 전용

```kotlin
// 올바른 방식: 생성자 주입
@Component
class IdempotentConsumerTemplate(
    private val processedEventService: ProcessedEventService,
)

// 금지: 필드 주입
@Autowired
private lateinit var processedEventService: ProcessedEventService
```

### @ConfigurationProperties — data class 사용

```kotlin
// 올바른 방식
@ConfigurationProperties(prefix = "outbox.poller")
data class OutboxPollerProperties(
    val enabled: Boolean = false,
    val maxRetryCount: Int = 3,
    val batchSize: Int = 100,
    val fixedDelay: Long = 1000
)
```

### 조건부 Bean — @ConditionalOnClass / @ConditionalOnProperty

common 모듈의 빈은 서비스에서 의존성이 존재할 때만 활성화되도록 조건부로 등록합니다:

```kotlin
// Kafka Acknowledgment가 클래스패스에 있을 때만 빈 등록
@Component
@ConditionalOnClass(name = ["org.springframework.kafka.support.Acknowledgment"])
class IdempotentConsumerTemplate(...)

// Feign 클라이언트가 클래스패스에 있을 때만 설정 활성화
@Configuration
@ConditionalOnClass(RequestInterceptor::class)
class FeignConfig(...)
```

### Repository 패턴

- 기본 CRUD: `JpaRepository<Entity, UUID>` 상속
- 복잡한 쿼리 (Querydsl): `{Entity}RepositoryCustom` 인터페이스 + `{Entity}RepositoryCustomImpl` 구현

```kotlin
interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID>, OutboxEventRepositoryCustom

interface OutboxEventRepositoryCustom {
    fun findUnpublishedEvents(batchSize: Int): List<OutboxEvent>
}

class OutboxEventRepositoryCustomImpl(
    private val queryFactory: JPAQueryFactory
) : OutboxEventRepositoryCustom {
    override fun findUnpublishedEvents(batchSize: Int): List<OutboxEvent> { ... }
}
```

---

## 4. JPA Entity 규칙

### 기본 구조

```kotlin
@Entity
@Table(name = "events", schema = "event_service")   // 스키마 명시 필수
class Event(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID) // UUID PK
    val id: UUID? = null,                           // nullable (영속화 전 null)

    @Column(nullable = false)
    val title: String,                              // 불변 필드는 val

    @ManyToOne(fetch = FetchType.LAZY)              // LAZY 필수 (EAGER 금지)
    @JoinColumn(name = "venue_id", nullable = false)
    val venue: Venue,

    @Enumerated(EnumType.STRING)                    // EnumType.STRING 필수 (ORDINAL 금지)
    @Column(nullable = false)
    val status: EventStatus = EventStatus.PREPARING,

    @Column(name = "published", nullable = false)
    var published: Boolean = false,                 // 변경 가능 필드는 var

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    val updatedAt: LocalDateTime? = null
) {
    // Hibernate 권장 equals/hashCode: id 기반, null-safe
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Event) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0
}
```

### 핵심 규칙

| 규칙 | 이유 |
|------|------|
| `class` 사용 (data class 금지) | Hibernate proxy, lazy loading 호환성 |
| UUID PK + `GenerationType.UUID` | 분산 환경에서 ID 충돌 방지 |
| `@Table(schema = "...")` 명시 | 스키마 격리 원칙 강제 |
| `FetchType.LAZY` 필수 | N+1 쿼리 방지 |
| `EnumType.STRING` 필수 | DB 마이그레이션 안전성 |
| `equals`/`hashCode` id 기반 재정의 | JPA 컨텍스트 내 동등성 보장 |
| 감사 타임스탬프 `@CreationTimestamp` / `@UpdateTimestamp` | 모든 Entity에 적용 |

---

## 5. API 응답 및 에러 처리

### 성공 응답

도메인 DTO를 직접 반환합니다. 래퍼 클래스(`ApiResponse<T>` 등)를 사용하지 않습니다.

```kotlin
// 올바른 방식: 도메인 DTO 직접 반환
@GetMapping("/{id}")
fun getEvent(@PathVariable id: UUID): EventResponse { ... }

// 금지: 불필요한 래퍼
@GetMapping("/{id}")
fun getEvent(@PathVariable id: UUID): ApiResponse<EventResponse> { ... }
```

### ErrorResponse 표준 포맷

```kotlin
data class ErrorResponse(
    val code: String,       // ErrorCode.code 값 (예: "EVENT_NOT_FOUND")
    val message: String,    // 사용자에게 표시할 한국어 메시지
    val timestamp: String,  // "yyyy-MM-ddTHH:mm:ss" 형식
    val traceId: String     // MDC traceId 또는 UUID fallback
) {
    companion object {
        fun of(errorCode: ErrorCode, message: String? = null): ErrorResponse { ... }
    }
}
```

### ErrorCode Enum

HTTP 상태 코드, 에러 코드 문자열, 한국어 메시지를 함께 정의합니다:

```kotlin
enum class ErrorCode(
    val status: HttpStatus,
    val code: String,
    val message: String
) {
    // 에러 코드는 서비스별로 그룹핑하여 주석 구분
    // Common
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다."),

    // Event
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "존재하지 않는 공연입니다."),
}
```

### BusinessException 계층

```kotlin
// common: 기반 클래스
open class BusinessException(
    val errorCode: ErrorCode,
    override val message: String = errorCode.message,
    override val cause: Throwable? = null
) : RuntimeException(message, cause)

// 서비스별 서브클래스 (각 서비스 exception/ 패키지에 위치)
class EventException(
    errorCode: ErrorCode,
    message: String = errorCode.message,
    cause: Throwable? = null
) : BusinessException(errorCode, message, cause)
```

### GlobalExceptionHandler

`@RestControllerAdvice`를 사용하며 common 모듈에 위치합니다.
서비스별 추가 예외 처리가 필요한 경우 서비스 내에 별도 `@RestControllerAdvice`를 추가합니다.

---

## 6. DTO 및 Validation

### 기본 구조

```kotlin
// 요청 DTO: data class + Jakarta Bean Validation
data class CreateReservationRequest(
    @field:NotNull(message = "scheduleId는 필수입니다.")
    val scheduleId: UUID?,

    @field:NotEmpty(message = "좌석을 1개 이상 선택해야 합니다.")
    @field:Size(max = 4, message = "최대 4개 좌석까지 선택 가능합니다.")
    val seatIds: List<UUID>
)

// 응답 DTO: data class + companion object factory (필요한 경우)
data class EventResponse(
    val id: UUID,
    val title: String,
    val artist: String,
    val status: EventStatus
) {
    companion object {
        fun from(event: Event): EventResponse = EventResponse(
            id = event.id!!,
            title = event.title,
            artist = event.artist,
            status = event.status
        )
    }
}
```

### Validation 메시지

Jakarta Bean Validation 메시지는 한국어로 작성합니다:

```kotlin
@field:NotBlank(message = "이메일은 필수입니다.")
@field:Email(message = "올바른 이메일 형식이 아닙니다.")
val email: String?,

@field:Size(min = 8, max = 20, message = "비밀번호는 8자 이상 20자 이하여야 합니다.")
val password: String?
```

### 중첩 DTO

복잡한 응답은 중첩 data class로 표현합니다:

```kotlin
data class EventDetailResponse(
    val id: UUID,
    val title: String,
    val schedules: List<ScheduleInfo>
) {
    data class ScheduleInfo(
        val id: UUID,
        val startAt: LocalDateTime,
        val status: ScheduleStatus
    )
}
```

---

## 7. Kafka 이벤트

### BaseEvent 구조

모든 Kafka 이벤트는 `BaseEvent`를 상속합니다:

```kotlin
// common 모듈: 추상 기반 클래스
abstract class BaseEvent(
    open val eventId: UUID = UUID.randomUUID(),
    open val eventType: String,
    open val aggregateId: UUID,
    open val aggregateType: String,
    open val version: String = "v1",
    open val timestamp: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    open val metadata: EventMetadata = EventMetadata()
)

data class EventMetadata(
    val correlationId: UUID = UUID.randomUUID(),
    val causationId: UUID? = null,
    val userId: UUID? = null
)

// 구체 이벤트: data class
data class PaymentSuccessEvent(
    override val aggregateId: UUID,
    override val aggregateType: String = "Payment",
    override val eventType: String = "PaymentSuccess",
    val payload: PaymentSuccessPayload
) : BaseEvent()
```

### KafkaTopicConfig — 토픽 매핑 중앙 관리

토픽명은 코드에 하드코딩하지 않고 `KafkaTopicConfig` enum만 사용합니다:

```kotlin
// 올바른 방식
val topic = KafkaTopicConfig.PAYMENT.topic

// 금지: 하드코딩
val topic = "payment.events"
```

### Outbox Pattern (Producer 서비스)

`Reservation Service`, `Payment Service`는 Kafka 발행 전 반드시 Outbox 패턴을 사용합니다:

```kotlin
// 비즈니스 로직 트랜잭션 내에서 Outbox 이벤트 저장
@Transactional
fun processPayment(...) {
    // 1. 비즈니스 로직
    val payment = paymentRepository.save(...)

    // 2. Outbox에 이벤트 저장 (Kafka 직접 발행 금지)
    outboxEventRepository.save(
        OutboxEvent(
            aggregateType = "Payment",
            aggregateId = payment.id!!,
            eventType = "PaymentSuccess",
            payload = objectMapper.writeValueAsString(event)
        )
    )
}
```

### IdempotentConsumerTemplate (Consumer 서비스)

`Reservation Service`, `Event Service`의 모든 Kafka Consumer는 `IdempotentConsumerTemplate`을 사용합니다:

```kotlin
@KafkaListener(topics = ["payment.events"], groupId = "reservation-payment-consumer")
fun handlePaymentEvent(event: PaymentSuccessEvent, acknowledgment: Acknowledgment) {
    idempotentConsumerTemplate.process(
        event = event,
        consumerService = "reservation-service",
        acknowledgment = acknowledgment
    ) { e ->
        // 비즈니스 로직만 작성 (중복 체크는 템플릿이 처리)
        reservationService.confirmReservation(e.aggregateId)
    }
}
```

### ExceptionClassifier — 재시도 가능 여부 분류

Consumer에서 예외가 발생하면 `ExceptionClassifier.isRetryable(e)`로 판단합니다:

- **재시도 가능**: `TimeoutException`, 네트워크 오류 → 지수 백오프 후 재시도
- **재시도 불가**: `ValidationException`, `DataIntegrityViolationException` → 즉시 DLQ 이동

---

## 8. 보안

### TraceId 전파

모든 서비스는 `X-Trace-Id` 헤더를 수신하여 MDC에 저장합니다.
API Gateway의 `TraceIdWebFilter`가 헤더를 생성하고 downstream으로 전파합니다.

```kotlin
// common 모듈의 TraceIdFilter가 자동 적용 (서비스별 추가 구현 불필요)
// MDC 키: "traceId"
// 헤더명: "X-Trace-Id"
```

### Internal API 인증

서비스 간 내부 API(`/internal/**`)는 `X-Service-Api-Key` 헤더로 인증합니다:

```kotlin
// InternalApiAuthInterceptor가 /internal/** 경로를 가로채어 검증
// application.yml에 internal.api.key 설정 필수

// 내부 API 컨트롤러
@RestController
@RequestMapping("/internal")
class InternalSeatController(...)
```

API Gateway는 `/internal/**` 경로를 외부에서 접근할 수 없도록 명시적으로 차단합니다.

### API Gateway 라우팅 보안

```yaml
# gateway: /internal/** 경로는 404 반환 (외부 차단)
# 서비스별 내부 API는 VPC 내부 통신으로만 허용
```

---

## 9. 로깅

### Logger 선언

`KotlinLogging.logger {}`를 사용합니다. `LoggerFactory.getLogger()`를 직접 사용하지 않습니다:

```kotlin
import io.github.oshai.kotlinlogging.KotlinLogging

class MyService {
    private val logger = KotlinLogging.logger {}
}
```

### 로그 레벨 사용 기준

```kotlin
logger.debug { "Processing event: eventId=$eventId" }   // 개발 디버깅
logger.info { "Event processed: eventId=$eventId" }     // 주요 비즈니스 플로우
logger.warn { "[${errorCode.code}] ${message}" }        // 예상된 예외 (BusinessException)
logger.error(ex) { "[INTERNAL_SERVER_ERROR] ..." }      // 예상치 못한 예외
```

### 구조화 로그 — Logstash JSON 인코더

운영 환경에서는 Logstash JSON 포맷으로 출력합니다. `traceId`는 MDC를 통해 모든 로그에 자동 포함됩니다.

```yaml
# application-local.yml: 텍스트 포맷 (개발 편의)
# application.yml (prod 프로필): JSON 포맷 (CloudWatch Logs 파싱)
```

### 로깅 금지 항목

비밀번호, JWT 토큰, 카드번호 등 민감 정보는 절대 로깅하지 않습니다.

---

## 10. 테스트

### 기술 스택

- **단위 테스트**: JUnit 5 + MockK + Kotest assertions
- **Spring 통합 테스트**: `@SpringBootTest` + SpringMockK (`@MockkBean`)
- **통합 테스트**: TestContainers (PostgreSQL, Kafka)
- **비동기 검증**: Awaitility

### 테스트 명명 — 한국어 백틱

```kotlin
@Test
fun `이벤트 ID로 조회 시 존재하지 않으면 EventException 발생`() { ... }

@Test
fun `헤더 없는 요청 시 UUID 생성하여 응답 헤더에 설정`() { ... }
```

### Given/When/Then 구조

```kotlin
@Test
fun `유효한 입력으로 이벤트 생성 시 저장된 이벤트 반환`() {
    // Given
    val request = CreateEventRequest(title = "BTS 콘서트", ...)
    every { eventRepository.save(any()) } returns savedEvent

    // When
    val result = eventService.createEvent(request)

    // Then
    result.title shouldBe "BTS 콘서트"
    verify(exactly = 1) { eventRepository.save(any()) }
}
```

### Kotest Assertions 사용

```kotlin
// Kotest 스타일
result shouldBe expected
result.shouldNotBeNull()
list shouldHaveSize 3
exception.shouldBeInstanceOf<EventException>()

// JUnit assertEquals 지양 (Kotest로 통일)
assertEquals(expected, result)
```

### TestContainers 통합 테스트

```kotlin
@SpringBootTest
@Testcontainers
class EventServiceIntegrationTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:18")
    }
}
```

### 비동기 테스트 — Awaitility

Kafka Consumer 등 비동기 로직은 Awaitility로 검증합니다:

```kotlin
await()
    .atMost(10, TimeUnit.SECONDS)
    .untilAsserted {
        val reservation = reservationRepository.findById(reservationId)
        reservation?.status shouldBe ReservationStatus.CONFIRMED
    }
```

---

## 11. Gradle 빌드

### Version Catalog 필수

모든 의존성 버전은 `gradle/libs.versions.toml`에서 관리합니다. `build.gradle.kts`에 버전을 직접 작성하지 않습니다:

```kotlin
// 올바른 방식: Version Catalog 참조
dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.bundles.kotlin)
}

// 금지: 직접 버전 명시
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web:3.5.10")
}
```

### Bundle 활용

자주 함께 사용되는 의존성은 bundle로 묶어서 사용합니다:

```toml
[bundles]
kotlin = ["kotlin-reflect", "kotlin-stdlib", "jackson-module-kotlin"]
test-base = ["spring-boot-starter-test", "mockk", "springmockk", "kotest-runner-junit5", "kotest-assertions-core"]
testcontainers = ["testcontainers-junit-jupiter", "testcontainers-postgresql", "testcontainers-kafka"]
```

### common 모듈 — java-library + api scope

```kotlin
// common/build.gradle.kts
plugins {
    `java-library`  // api/implementation scope 사용 가능
}

dependencies {
    api(libs.spring.boot.starter.web)         // 서비스에도 노출되어야 하는 의존성
    implementation(libs.some.internal.lib)    // common 내부에서만 사용
    compileOnly(libs.spring.boot.starter.security) // 선택적 의존성
}
```

### 서비스 모듈 — implementation scope

서비스 모듈은 `implementation`을 사용합니다. `api` scope를 남용하지 않습니다.

### 라이브러리 모듈 설정

```kotlin
// common/build.gradle.kts: bootJar 비활성화 필수
tasks.bootJar { enabled = false }
tasks.jar { enabled = true }
```

---

## 12. 데이터베이스

### 테이블/컬럼 명명

| 대상 | 규칙 | 예시 |
|------|------|------|
| 테이블명 | 복수형 snake_case | `outbox_events`, `processed_events` |
| 컬럼명 | snake_case | `aggregate_type`, `created_at` |
| 인덱스명 | `idx_{table}_{columns}` | `idx_events_status` |
| 제약조건명 | `uq_{table}_{columns}` | `uq_processed_events_event_id_consumer` |
| FK명 | `fk_{table}_{ref_table}` | `fk_events_venues` |

### 스키마 격리

```sql
-- 각 서비스는 자신의 스키마만 사용
ticketing/user_service     -- User Service 전용
ticketing/event_service    -- Event Service 전용
ticketing/reservation_service
ticketing/payment_service
ticketing/common           -- outbox_events, processed_events (모든 서비스 접근 가능)
```

서비스 간 직접 JOIN 또는 크로스 스키마 쿼리는 절대 금지입니다.

### UUID PK

```sql
id UUID PRIMARY KEY DEFAULT gen_random_uuid()
```

### COMMENT ON 필수

테이블과 컬럼에 한국어 주석을 작성합니다:

```sql
COMMENT ON TABLE event_service.events IS '공연 정보';
COMMENT ON COLUMN event_service.events.title IS '공연 제목';
```

### Redis KEYS 명령 금지

Production 환경에서 `KEYS` 명령 사용은 절대 금지입니다 (O(N) 복잡도).
대신 `SET`, `HASH` 등 O(1) 자료구조를 사용합니다:

```kotlin
// 올바른 방식: Redis SET으로 HOLD 좌석 목록 관리
redisTemplate.opsForSet().add("hold_seats:$scheduleId", seatId)

// 금지: KEYS 명령
redisTemplate.keys("seat:hold:$scheduleId:*")
```

---

## 13. Docker

### 컨테이너/볼륨 명명

| 대상 | 패턴 | 예시 |
|------|------|------|
| 컨테이너명 | `ticket-{service}` | `ticket-postgres`, `ticket-valkey`, `ticket-kafka` |
| 볼륨명 | `{service}_data` | `postgres_data`, `valkey_data` |

### 이미지 버전 고정

`latest` 태그 사용을 금지합니다. 항상 구체적인 버전을 명시합니다:

```yaml
# 올바른 방식
image: postgres:18.0
image: valkey/valkey:8.1.5

# 금지
image: postgres:latest
```

### 시크릿 파일 패턴

민감 정보는 Docker secrets 또는 환경변수 파일로 관리합니다. `docker-compose.yml`에 평문으로 직접 작성하지 않습니다.

---

## 14. Git 컨벤션

### Conventional Commits

```
<type>(<scope>): <description> #<issue-number>

feat(reservation): 좌석 선점 API 구현 #45
fix(queue): 대기열 중복 진입 방지 로직 수정 #67
refactor(common): KafkaTopicConfig 중앙화 #89
chore(build): libs.versions.toml 의존성 업데이트
docs(api): 예매 API 명세서 작성
perf(event): 공연 목록 캐시 TTL 최적화 #34
test(payment): SAGA 패턴 통합 테스트 추가 #78
```

**허용 타입**: `feat`, `fix`, `refactor`, `chore`, `docs`, `perf`, `test`

### 커밋 메시지 규칙

- 한국어 또는 영어 모두 허용
- 이슈 번호는 `#N` 형식으로 참조
- 제목은 50자 이하 권장
- 본문은 선택사항 (복잡한 변경사항 설명 시)

### 줄바꿈

모든 파일은 LF 줄바꿈을 사용합니다 (CRLF 금지).

### 브랜치 전략

```
main          # 프로덕션 릴리스
develop       # 개발 통합 브랜치 (PR 대상)
feature/*     # 기능 개발 (예: feature/reservation-hold-api)
hotfix/*      # 긴급 수정 (예: hotfix/queue-duplicate-entry)
```

PR은 `develop` 브랜치를 base로 생성합니다.

---

## 참고 문서

- [요구사항 명세](../docs/REQUIREMENTS.md)
- [아키텍처 개요](../docs/architecture/)
- [API 명세서](../docs/specification/)
