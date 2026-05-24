# TASK.md — Ticket Queue 남은 작업 ledger

> **목적**: 병렬 세션들이 동시에 작업할 때 동일 이슈를 중복 점유하지 않고, 의존성을 위배하지 않도록 공유 체크리스트로 사용한다.
>
> **Source of truth**: GitHub Issues (open 상태). 본 파일은 작업 순서와 점유 현황의 ledger이며, 이슈의 상세 작업 항목은 각 GitHub 이슈 본문을 참조한다.
>
> **Last sync**: 2026-05-24 / 17 open issues

---

## Protocol — 병렬 세션 작업 규약

### 기본 흐름
1. 이 파일을 **읽어서** `[ ]` 상태이고 `Claim` 컬럼이 비어 있고 **의존성이 모두 `[x]`인** 가장 윗 줄의 이슈를 선택한다.
2. 의존성 중 하나라도 미완료(`[ ]`)면 **건너뛰고** 다음 후보로 진행한다 (단, Soft dep는 mock으로 선행 가능).
3. `Claim` 컬럼에 자신의 세션 식별자(예: `session-74b9a2ef`)를 기입하고 즉시 commit + push 한다.
   - push가 non-fast-forward로 실패하면 다른 세션이 먼저 claim한 것이므로 pull --rebase 후 다른 이슈를 다시 선택한다.
4. `git fetch origin && git worktree add -b <branch-name> .worktrees/<branch-name> origin/develop` 으로 worktree 생성.
5. 해당 worktree 안에서 이슈 본문의 체크리스트를 구현하고, Conventional Commits 규칙으로 커밋한다.
6. push 후 `gh pr create --base develop` 으로 PR 생성, PR URL을 이 파일 `PR` 컬럼에 기입하고 commit + push.
7. PR이 **머지된 시점**에 체크박스를 `[x]`로 바꾸고 commit + push (PR 머지 전에는 절대 체크하지 않는다).

### Base branch
- 모든 worktree branch는 `origin/develop`에서 파생한다.
- merge target도 `develop`이다.

### Branch 명명 규칙
- 신규 기능: `feature/<issue-number>-<kebab-slug>` 예: `feature/55-reservation-outbox`
- 버그 수정: `fix/<issue-number>-<kebab-slug>`
- 리팩토링: `refactor/<issue-number>-<kebab-slug>`

### 의존성(Deps) 컬럼 표기
- `#NN` — **Hard dependency**: 해당 이슈의 PR이 develop에 머지되어야 본 이슈 시작 가능
- `(#NN)` — **Soft dependency**: API 계약 또는 mock으로 병렬 진행 가능, 통합 시점에 sync 필요
- `—` — 의존성 없음

### Claim 규칙
- 동시에 두 세션이 동일 이슈 claim 시도 → git push의 fast-forward 검증으로 자연스럽게 한 세션만 성공.
- claim 후 24시간 내 PR을 올리지 못하면 다른 세션이 claim을 강제 해제할 수 있다 (claim 컬럼 비우고 commit).
- PR이 close/draft 상태로 1주 이상 정체되면 동일하게 claim 해제 가능.

### Blocked 표기
- 의존성 미해소로 진행 불가능한 row의 `Claim` 컬럼에 `BLOCKED:#NN` 형식으로 기입 (옵션).

---

## Tier 0 — Common Foundation

모든 Outbox 패턴 구현(#55, #63)과 후속 Kafka Consumer 작업의 기반이 되는 공용 헬퍼. **반드시 최우선으로 머지되어야 한다.**

| # | Done | Issue | Title | Deps | Claim | Branch | PR |
|---|------|-------|-------|------|-------|--------|----|
| 1 | [x] | [#228](https://github.com/paircoders/ticket-queue/issues/228) | [Common/Reservation] OutboxEventRecorder 헬퍼 도입 및 ReservationService 적용 | — | session-bec7620d | `refactor/228-outbox-event-recorder` | [#229](https://github.com/paircoders/ticket-queue/pull/229) |

---

## Tier 1 — Outbox 패턴 (서비스별)

서비스별 Transactional Outbox 구현. **#228의 `OutboxEventRecorder`를 재사용**해야 하므로 Tier 0 머지 후 진행.

| # | Done | Issue | Title | Deps | Claim | Branch | PR |
|---|------|-------|-------|------|-------|--------|----|
| 2 | [ ] | [#55](https://github.com/paircoders/ticket-queue/issues/55) | [Reservation] Transactional Outbox 패턴 구현 | #228 | session-ralph-228-55 | `feature/228-55-outbox-recorder-infra` |  |
| 3 | [ ] | [#63](https://github.com/paircoders/ticket-queue/issues/63) | [Payment] Transactional Outbox 패턴 구현 | #228 |  | `feature/63-payment-outbox` |  |

---

## Tier 2 — 서비스 핵심 API & 독립 작업

Outbox 위에서 동작하거나 완전히 독립적인 핵심 백엔드 API. **Tier 1의 해당 서비스 Outbox가 머지된 후** Payment API들이 진행 가능.

| # | Done | Issue | Title | Deps | Claim | Branch | PR |
|---|------|-------|-------|------|-------|--------|----|
| 4 | [ ] | [#59](https://github.com/paircoders/ticket-queue/issues/59) | [Payment] 결제 승인 API 구현 | #63 |  | `feature/59-payment-confirm-api` |  |
| 5 | [ ] | [#61](https://github.com/paircoders/ticket-queue/issues/61) | [Payment] Timeout 및 Circuit Breaker 설정 | #59 |  | `feature/61-payment-resilience` |  |
| 6 | [ ] | [#64](https://github.com/paircoders/ticket-queue/issues/64) | [Payment] 결제 조회 API 구현 | #59 |  | `feature/64-payment-query-api` |  |
| 7 | [ ] | [#52](https://github.com/paircoders/ticket-queue/issues/52) | [Reservation] 예매 내역 조회 API 구현 | — |  | `feature/52-reservation-query-api` |  |
| 8 | [ ] | [#54](https://github.com/paircoders/ticket-queue/issues/54) | [Reservation] 선점 만료 자동 취소 배치 | #55 |  | `feature/54-reservation-expire-batch` |  |

---

## Tier 3 — SAGA & Kafka Consumer Integration

서비스 간 이벤트 체인이 필요한 통합 작업. **양쪽 Outbox(#55, #63)와 결제 승인(#59)이 모두 머지된 후 진행.**

| # | Done | Issue | Title | Deps | Claim | Branch | PR |
|---|------|-------|-------|------|-------|--------|----|
| 9 | [ ] | [#53](https://github.com/paircoders/ticket-queue/issues/53) | [Reservation] Kafka Consumer - 결제 이벤트 처리 | #55, #63, #59 |  | `feature/53-reservation-payment-consumer` |  |
| 10 | [ ] | [#62](https://github.com/paircoders/ticket-queue/issues/62) | [Payment] SAGA 패턴 - 보상 트랜잭션 구현 | #59, #53 |  | `feature/62-payment-saga-compensation` |  |

---

## Tier 4 — User Service 부가 기능 (독립 병렬 가능)

다른 서비스에 의존하지 않으므로 어떤 Tier와도 병렬 진행 가능. **세 이슈 모두 독립적이라 동시에 세 세션이 작업 가능.**

| # | Done | Issue | Title | Deps | Claim | Branch | PR |
|---|------|-------|-------|------|-------|--------|----|
| 11 | [ ] | [#27](https://github.com/paircoders/ticket-queue/issues/27) | [User] 프로필 조회/수정 API 구현 | — |  | `feature/27-user-profile-api` |  |
| 12 | [ ] | [#28](https://github.com/paircoders/ticket-queue/issues/28) | [User] 비밀번호 변경 API 구현 | — |  | `feature/28-user-password-change` |  |
| 13 | [ ] | [#29](https://github.com/paircoders/ticket-queue/issues/29) | [User] 회원 탈퇴 API 구현 | — |  | `feature/29-user-withdraw` |  |

---

## Tier 5 — Frontend (백엔드 API 의존 / mock 선행 가능)

백엔드 API에 의존하지만 OpenAPI/Type 계약 기반 mock으로 선행 작업 가능 (Soft dependency). 통합 테스트 시점에 실제 API와 sync한다.

| # | Done | Issue | Title | Deps | Claim | Branch | PR |
|---|------|-------|-------|------|-------|--------|----|
| 14 | [ ] | [#121](https://github.com/paircoders/ticket-queue/issues/121) | [Frontend] 마이페이지 (프로필, 예매 내역) | (#27), (#52) |  | `feature/121-frontend-mypage` |  |
| 15 | [ ] | [#119](https://github.com/paircoders/ticket-queue/issues/119) | [Frontend] 결제 페이지 (PortOne SDK, 3단계 결제 플로우) | (#59) |  | `feature/119-frontend-payment` |  |
| 16 | [ ] | [#120](https://github.com/paircoders/ticket-queue/issues/120) | [Frontend] 결제 완료/실패 페이지 | #119 |  | `feature/120-frontend-payment-result` |  |
| 17 | [ ] | [#122](https://github.com/paircoders/ticket-queue/issues/122) | [Frontend] 성능 최적화, SEO 마무리, 번들 분석 | #119, #120, #121 |  | `feature/122-frontend-perf-seo` |  |

---

## 진행 현황 요약

- **총 17 이슈** · 완료 0 · 진행 중 0 · 미시작 17
- 다음 시작 가능 (의존성 없거나 충족): `#228`, `#52`, `#27`, `#28`, `#29`
- mock 기반 soft 선행 가능: `#119`(soft: #59), `#121`(soft: #27, #52)
- hard 의존성으로 즉시 시작 불가: `#120`(needs #119), `#122`(needs #119, #120, #121)

> 위 요약은 수동 갱신 권장. 정확한 카운트는 표의 체크박스를 직접 확인하세요.
