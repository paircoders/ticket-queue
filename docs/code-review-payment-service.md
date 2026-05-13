# Code Review: Payment Service 연동 구현 (#58)

**브랜치:** `feature/58`  
**리뷰 범위:** Payment Service 초기 구현 + Reservation Internal API + PortOne 연동  
**리뷰 일자:** 2026-05-12

---

## 목차

1. [P0 — 즉시 수정 필요](#p0--즉시-수정-필요)
2. [P1 — 다음 PR 전 처리](#p1--다음-pr-전-처리)
3. [P2 — 후속 PR 개선](#p2--후속-pr-개선)
4. [P3 — 기술 부채 등록](#p3--기술-부채-등록)
5. [긍정적 관찰](#긍정적-관찰)

---

## P0 — 즉시 수정 필요

### 1. 외부 API 호출과 DB 트랜잭션 정합성 결함

**파일:** `payment-service/.../service/PaymentService.kt:59-76`

`portoneClient.preRegisterPayment(...)` 성공 후 `paymentRepository.save(...)` 실패 시, PortOne에는 `paymentKey`가 등록되고 로컬 DB에는 Payment 엔티티가 없는 **불일치 상태**가 발생한다.

- `@Transactional` 범위 안에서 외부 HTTP 호출 → PortOne 응답 지연 시 DB 커넥션 점유 → 커넥션 풀 고갈 위험
- SAGA 패턴 설계 의도와 충돌

**권장 수정:**
```
1. DB save(PENDING 상태)를 먼저 수행하고 commit 후 PortOne pre-register 호출
2. 또는 보정 잡(reconciliation)으로 orphan paymentKey 정리
3. 외부 호출은 @Transactional 외부로 분리
```

---

### 2. `ReservationDetailResponse` DTO 이중 정의 + `status` 타입 불일치

**파일 1:** `reservation-service/.../controller/ReservationInternalController.kt:42` — `status: ReservationStatus` (Enum)  
**파일 2:** `payment-service/.../client/ReservationServiceClient.kt:26` — `status: String`

동일한 wire format을 나타내는 DTO가 두 서비스에 별도 정의되어 있고, `status` 타입이 불일치한다.  
`PaymentService.kt:40`에서 `reservation.status != "PENDING"` 같은 **raw string 비교**로 이어지며, Reservation Service에서 enum 이름이 변경되거나 `@JsonValue`가 추가되면 **컴파일 타임에 감지 불가능한 silent failure** 발생.

**권장 수정:**
```
단기: PaymentService 내부에 자체 Enum을 선언하거나 companion object 상수로 추출
      → if (reservation.status != ReservationStatus.PENDING.name)
장기: common 모듈에 공유 Enum/DTO 배치 (reservation-contract 모듈 추가)
```

---

### 3. PortOne pre-register 실패 경로 테스트 미커버

**파일:** `payment-service/.../test/.../service/PaymentServiceTest.kt`

`portoneClient.preRegisterPayment(...)` 가 `FeignException`/`RetryableException`을 던지는 케이스가 전혀 없다. 결제 도메인에서 외부 API 실패는 핵심 분기점이며, P0-1번 정합성 결함과 직접 연결된다.

**권장 추가:**
```kotlin
@Test
fun portonePreRegisterFails() {
    every { portoneClient.preRegisterPayment(any(), any(), any()) } throws FeignException(...)
    // save가 호출되지 않아야 함
    verify(exactly = 0) { paymentRepository.save(any()) }
}
```

---

### 4. 만료 시나리오 통합 테스트 미커버

**파일:** `payment-service/.../test/.../controller/PaymentControllerIntegrationTest.kt:188-225`

`BusinessFailure` 중첩 클래스에 `amountMismatch`, `forbidden` 케이스만 있고 Hold 만료 시나리오(`status != PENDING`, `holdExpiresAt` 경과)에 대한 통합 테스트가 없다. 컨트롤러 → 서비스 → ErrorCode → HTTP status 매핑까지 end-to-end 검증 누락.

**권장 추가:**
```
- holdExpiredByStatus: status="CONFIRMED" → 400 + ErrorCode.HOLD_EXPIRED
- holdExpiredByTime: holdExpiresAt = past → 400 + ErrorCode.HOLD_EXPIRED
```

---

## P1 — 다음 PR 전 처리

### 5. Stringly-typed 상태 비교

**파일:** `payment-service/.../service/PaymentService.kt:40`

```kotlin
if (reservation.status != "PENDING") {  // ← raw string 비교
    throw PaymentException(ErrorCode.HOLD_EXPIRED)
}
```

오타 미탐지, IDE 리팩토링 지원 불가. P0-2번과 동일 원인.

**권장 수정:**
```kotlin
companion object { const val STATUS_PENDING = "PENDING" }
if (reservation.status != STATUS_PENDING) { ... }
// 또는 Enum 공유 후 → reservation.status != ReservationStatus.PENDING
```

---

### 6. Feign URL 프로퍼티 키 컨벤션 분기

**파일 1:** `payment-service/.../client/ReservationServiceClient.kt:13` → `feign.client.config.reservation-service.url`  
**파일 2:** 기존 `queue-service/.../client/EventServiceClient.kt` → `spring.cloud.openfeign.client.config.event-service.url`

신규 추가된 `ReservationServiceClient`가 비표준 키를 사용하여 기존 컨벤션 분기를 더 굳혔다. Spring Cloud OpenFeign 공식 키는 `spring.cloud.openfeign.client.config.*`.

**권장 수정:**
```
전체 Feign client URL 키를 spring.cloud.openfeign.client.config.{name}.url 로 통일
```

---

### 7. 두 검증 분기가 동일 `ErrorCode(HOLD_EXPIRED)` 반환 — 의미 충돌

**파일:** `payment-service/.../service/PaymentService.kt:40-46`

```kotlin
if (reservation.status != "PENDING") throw PaymentException(ErrorCode.HOLD_EXPIRED)  // 상태 문제
if (!now.isBefore(holdExpiresAt))    throw PaymentException(ErrorCode.HOLD_EXPIRED)  // 시간 만료
```

`CANCELLED` 상태와 시간 초과는 다른 사건인데 동일 에러로 반환 → 클라이언트에게 부정확한 메시지 전달.

**권장 수정:**
```
RESERVATION_NOT_PAYABLE (상태가 PENDING 아님) vs HOLD_EXPIRED (시간 초과) 분리
```

---

### 8. `holdExpiresAt` timezone 불일치 위험

**파일 1:** `payment-service/.../service/PaymentService.kt:44` — `LocalDateTime.now(ZoneOffset.UTC)` 사용  
**파일 2:** `reservation-service/.../test/.../ReservationInternalControllerTest.kt:55` — zone 없는 `LocalDateTime.now()` 사용

Reservation Service가 시스템 기본 zone(예: KST)으로 `holdExpiresAt`을 저장하고, Payment Service가 UTC 기준으로 비교하면 **9시간 일찍 만료 처리**될 수 있다.

**권장 수정:**
```
양쪽 서비스 모두 Instant 또는 LocalDateTime.now(ZoneOffset.UTC)로 통일
테스트 픽스처도 동일 zone 기준으로 수정
```

---

### 9. Reservation 조회 실패(404/네트워크 에러) 테스트 미커버

**파일:** `payment-service/.../test/.../service/PaymentServiceTest.kt`

`reservationServiceClient.getReservation(...)` 이 404나 네트워크 에러를 던지는 케이스가 없다. 실서비스에서 결제 시점에 reservation이 만료/삭제되는 경합 상황은 흔하다.

**권장 추가:**
```
- FeignException.NotFound 케이스 → 어떤 ErrorCode로 변환되는지 검증
- RetryableException 케이스
```

---

### 10. `PortoneProperties.storeId`/`channelKey` 매 요청마다 null 체크

**파일:** `payment-service/.../service/PaymentService.kt:52-55`

```kotlin
val storeId = portoneProperties.storeId ?: error("external.portone.store-id is required")
val channelKey = portoneProperties.channelKey ?: error("external.portone.channel-key is required")
```

불변 설정값임에도 매 결제 요청마다 검증 수행. `apiSecret`은 이미 `init {}`에서 부팅 시 검증하는데 이 두 값만 빠짐. `error()`는 `IllegalStateException`을 던져 운영 환경에서 5xx로 그대로 노출될 위험도 있음.

**권장 수정:**
```kotlin
// PortoneProperties.kt
init {
    require(!storeId.isNullOrBlank()) { "external.portone.store-id is required" }
    require(!channelKey.isNullOrBlank()) { "external.portone.channel-key is required" }
}
// → storeId, channelKey를 String(non-null)으로 선언, PaymentService에서 ?: error 제거
```

---

### 11. 동일 `reservationId` 중복 결제 시도 동작 미정의/미테스트

**파일:** 통합 테스트 전체

같은 사용자가 재시도로 동일 `reservationId`에 대해 결제를 두 번 요청하는 경우의 동작이 정의되어 있지 않다. Payment 테이블의 unique constraint(어떤 컬럼 기준인지) 확인 필요.

**권장 추가:**
```
concurrentDuplicatePayment 통합 테스트:
같은 reservationId로 두 번 POST → 두 번째는 거부(또는 명시적으로 허용 문서화)
```

---

### 12. `X-Service-Api-Key` 헤더 보안 검증 테스트 미커버

**파일:** `reservation-service/.../test/.../controller/ReservationInternalControllerTest.kt`

`standaloneSetup`으로 구성되어 보안 필터 제외. `/internal/**` 경로의 핵심 보안 메커니즘인 `InternalApiAuthInterceptor`가 어떤 테스트에서도 검증되지 않는다.

**권장 추가:**
```
@SpringBootTest 통합 테스트로:
- X-Service-Api-Key 헤더 누락 → 401/403
- 잘못된 키 → 401/403
- 올바른 키 → 200
```

---

## P2 — 후속 PR 개선

### 13. `PaymentStatus` 자기 전이 + early return 이중 가드

**파일:** `payment-service/.../entity/PaymentStatus.kt:9-10`, `Payment.kt:67,78`

```kotlin
SUCCESS to setOf(SUCCESS, REFUNDED),  // SUCCESS→SUCCESS 자기 전이
FAILED  to setOf(FAILED),             // FAILED→FAILED 자기 전이
```

`markSuccess`/`markFailed`에 이미 `if (status == TARGET) return` 가드가 있어 두 메커니즘이 같은 케이스를 이중 가드. `FAILED to setOf(FAILED)` 항목은 early return에 의해 `canTransitionTo` 체크에 도달하지 않아 사실상 죽은 코드.

**권장 수정:**
```kotlin
PENDING to setOf(SUCCESS, FAILED),
SUCCESS to setOf(REFUNDED),   // 자기 전이 제거
FAILED  to emptySet(),        // FAILED는 종결 상태로 명시
REFUNDED to emptySet()
```

---

### 14. `SecurityConfig` 보일러플레이트 5개 서비스 중복

**파일:** `payment-service`, `reservation-service`, `queue-service`, `event-service`, `user-service` 각 `SecurityConfig.kt`

신규 추가된 `payment-service/SecurityConfig.kt`가 `reservation-service`와 99% 동일. `csrf disable`, STATELESS, `GatewayAuthFilter`, `SecurityErrorHandlers` 등록이 5개 서비스에서 반복.

**권장 수정:**
```kotlin
// common-web/security에 헬퍼 추가
fun standardChain(http: HttpSecurity, objectMapper: ObjectMapper, customize: (AuthorizeHttpRequestsConfigurer<*>.AuthorizationManagerRequestMatcherRegistry) -> Unit): SecurityFilterChain
// 각 서비스는 endpoint 권한 규칙만 람다로 주입
```

---

### 15. `ReservationInternalController` 내부에 DTO 인라인 정의

**파일:** `reservation-service/.../controller/ReservationInternalController.kt:42-50`

같은 서비스에 `dto/ReservationDto.kt`가 존재하는데 새 컨트롤러는 DTO를 inner class로 정의 → 서비스 내 DTO 위치 컨벤션 분기.

**권장 수정:**
```
dto/ReservationDto.kt 내부에 ReservationDetailResponse 추가
또는 dto/internal/ReservationInternalDto.kt 별도 파일로 분리
```

---

### 16. `createPayment` 메서드 55줄 — 단일 책임 미흡

**파일:** `payment-service/.../service/PaymentService.kt:33-87`

예약 검증(4분기) + PortOne 설정 검증 + PortOne pre-register 호출 + DB 저장 + 응답 구성이 한 메서드에 혼재.

**권장 수정:**
```kotlin
private fun validateReservation(reservation, userId, requestedAmount) { ... }
// PortoneProperties.init에서 storeId/channelKey 검증 시 (b) 자동 제거
```

---

### 17. `holdExpiresAt` 경계값 테스트 미커버

**파일:** `payment-service/.../test/.../service/PaymentServiceTest.kt:147`

`now == holdExpiresAt`인 정확한 경계값 케이스 없음. 시간 의존 코드는 `Clock`을 주입해 결정적으로 검증해야 한다.

**권장 추가:**
```kotlin
// Clock 주입 패턴 도입 후
@Test fun holdExpiredAtExactBoundary() {
    val fixedNow = LocalDateTime.now(ZoneOffset.UTC)
    every { reservationServiceClient.getReservation(any()) } returns
        buildReservation(holdExpiresAt = fixedNow)  // now == expiry → 만료
    assertThrows<PaymentException> { paymentService.createPayment(userId, request) }
        .errorCode shouldBe ErrorCode.HOLD_EXPIRED
}
```

---

### 18. `PaymentMethod` enum 단일 값 — 미완성 신호

**파일:** `payment-service/.../entity/PaymentMethod.kt`

```kotlin
enum class PaymentMethod { CARD }  // 단일 값
```

`CreateRequest.paymentMethod = PaymentMethod.CARD` 기본값으로 사실상 항상 CARD. 명세에 다른 결제수단이 예정되어 있지 않다면 enum을 제거하거나 TODO 주석으로 추적 필요.

---

### 19. `PortoneFeignConfig` vs 전역 `FeignConfig` 로깅 정책 충돌

**파일:** `common-web/.../config/PortoneFeignConfig.kt`, `FeignConfig.kt`

- 전역 `FeignConfig`에 `Logger.Level.FULL` 설정
- PortOne 전용으로 `Logger.Level.BASIC`으로 다운그레이드

일관성 없는 로깅 정책. `[PortOne]` prefix가 필요하다면 SLF4J MDC 또는 loggerName 매핑으로 처리하고 전역 정책에 맞추는 편이 낫다.

---

### 20. `ReservationInternalController`의 순차 2-쿼리

**파일:** `reservation-service/.../controller/ReservationInternalController.kt:27-30`

```kotlin
val reservation = reservationRepository.findById(reservationId).orElseThrow { ... }
val seatIds = reservationSeatRepository.findByReservationId(reservationId).map { it.seatId }
```

결제 hot-path에서 매 요청당 SELECT 2회. 트래픽 × 2 DB 부하.

**권장 수정:**
```
@EntityGraph(attributePaths = ["seats"])로 join fetch
또는 JPQL/Projection으로 단일 쿼리로 병합
```

---

### 21. `PaymentControllerIntegrationTest` 픽스처 중복

**파일:** `payment-service/.../test/.../controller/PaymentControllerIntegrationTest.kt`

```kotlin
mockMvc.perform(post("/payments").header(...).header(...).contentType(...).content(...))
```
위 패턴이 7번 반복.

**권장 수정:**
```kotlin
private fun postPayment(body: Map<String, Any?>, userId: UUID? = this.userId): ResultActions
```

---

### 22. `paymentMethod` 잘못된 enum 값 입력 테스트 미커버

**파일:** `payment-service/.../test/.../controller/PaymentControllerIntegrationTest.kt`

`"paymentMethod": "BITCOIN"` 같은 잘못된 enum 문자열 입력 시 Jackson이 어떤 응답을 반환하는지 검증 없음.

**권장 추가:**
```
invalidPaymentMethod 테스트: "BITCOIN" 입력 → 400
```

---

## P3 — 기술 부채 등록

### 23. `PaymentException` 래퍼 패턴 5개 서비스 중복

모든 서비스의 `XxxException`이 `BusinessException` 래퍼로 글자 단위 동일. 일관된 패턴을 따른 점은 긍정적이나 중복.

**선택지:**
```
(a) BusinessException을 직접 throw하고 서비스 식별은 errorCode prefix로 구분
(b) 서비스별 분리가 운영상 의미 있다면 유지 (현 방향)
```

---

### 24. `Payment.refund()`에 idempotent early return 누락

**파일:** `payment-service/.../entity/Payment.kt:87-92`

`markSuccess`/`markFailed`는 `if (status == TARGET) return`이 있지만 `refund()`는 없어 형제 메서드와 일관성 없음.

---

### 25. `DateTimeUtils.now()` 데드코드

**파일:** `common-core/.../util/DateTimeUtils.kt`

`LocalDateTime.now(UTC)` 헬퍼가 존재하지만 코드베이스 어디에서도 사용되지 않는다. 전사 표준으로 강제하거나 제거 중 하나로 결정 필요.

---

### 26. `PortoneTokenService.synchronized(this)` 잠재적 lock contention

**파일:** `common-web/.../external/portone/PortoneTokenService.kt:39-49`

토큰 만료 임박 구간(2분)에서 동시 결제 요청이 모두 `synchronized(this)` 진입 시도. K-pop 콘서트처럼 트래픽이 한 번에 몰리는 환경에서 잠재적 stall.

**권장 개선:**
```
백그라운드 갱신 스케줄러로 hot-path lock 진입 가능성 제거
만료 임박 기준을 2분 → 5분으로 확대하여 여유 확보
```

---

## 긍정적 관찰

| 항목 | 설명 |
|------|------|
| PortOne 캡슐화 | `PortoneFeignClient`, `PortoneTokenService`, `PortoneProperties` 모두 `common-web`에 배치, User/Payment Service 양쪽에서 재사용 |
| 내부 API 키 자동 주입 | `InternalFeignConfig` 재사용으로 `X-Service-Api-Key` 헤더 주입 자동화 |
| 상태 전이 캡슐화 | `PaymentStatus.canTransitionTo()`로 전이 규칙을 enum이 책임지는 OOP 패턴 |
| Entity invariant | `Payment.init {}`으로 `paymentKey`, `amount` 불변 조건을 entity가 직접 보장 |
| equals/hashCode | JPA entity 표준 패턴 (`id` 기반, null-safe) 준수 |
| 단위 테스트 | MockK 기반, 핵심 비즈니스 분기(forbidden/amountMismatch/holdExpired) 5가지 커버 |
| Testcontainers | 통합 테스트에서 실제 PostgreSQL 사용으로 스키마 검증 |
| 빈 Validation | `@field:NotNull`, `@field:Positive` Kotlin property 타깃 정확히 사용 |
| DTO 분리 | `@RequestBody @Valid` + 별도 DTO 클래스로 controller/service 경계 유지 |

---

*Generated by code review agents (Reuse / Quality / Efficiency+Test) on 2026-05-12*
