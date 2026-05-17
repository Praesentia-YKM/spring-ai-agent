# ADR-007: `PerformanceLoggingAdvisor` 등록 위치 + `Category` enum 5값 유지

- **Status:** Accepted
- **Date:** 2026-05-17
- **Round:** 1

## Context

두 결정 사항을 한 ADR 로 묶었다 (둘 다 4단계 마무리 단계).

1. `PerformanceLoggingAdvisor` 를 어떤 엔드포인트에 등록할지.
2. `SupportResponse.Category` enum 의 5값(`ORDER, DELIVERY, REFUND, PAYMENT, ETC`) 을 유지할지 확장할지.

## Decision

### A. Advisor 등록 위치
**`SupportController` + `PromptLabController` 둘 다 `.defaultAdvisors(performanceAdvisor)`** 로 등록.

- `SupportController` — 1·4단계 검증 결과 토큰 로그 캡처.
- `PromptLabController` — System Prompt 2배 실험(실험 D)에서 토큰 측정 위해 필수.
- `StreamingChatController` 는 등록 안 함 (streaming 메타데이터는 일반적으로 별도 메커니즘).

### B. Category enum
**현재는 5값 유지.** 단, Round 후반 확장 권장.

- 현재: `ORDER, DELIVERY, REFUND, PAYMENT, ETC` (Quest 기본값).
- 권장 확장: `COMPLAINT, PROMOTION` 추가 → 7값.

## Consequences

### A. Advisor 관련

**긍정:**
- `PromptLabController` 도 토큰 메타데이터 받아 정량 실험 가능 (실험 D 성립).
- `getOrder() = 100` 으로 체인 가장 바깥에 위치 → 다른 Advisor 가 처리한 시간까지 포함된 **총 LLM 왕복 시간** 측정.
- `null` 방어 — provider 메타데이터 누락(Ollama 일부 버전) / 캐싱/Mock Advisor 우회 시에도 안전.

**부정 / Trade-off:**
- `PerformanceLoggingAdvisor` 본문 예외 발생 시 LLM 호출 전체가 실패 (현재 `try/catch` 미적용). Production 에서는 로깅 실패가 본 응답에 영향 주지 않도록 격리 필요.

### B. Category 관련

**5값 유지 사유:**
- Quest 기본값. 변경 시 평가 답안 일관성 흐트러질 수 있음.
- 학습자가 도메인 감각으로 확장 결정을 README 에 기록하는 것이 학습 가치.

**관찰된 한계 (확장 동기):**
- 시나리오 2 (취소·환불) → `ORDER` 로 분류 (정답은 `REFUND`). 두 카테고리 경계가 모호.
- 시나리오 3 (라이더 사고) → `DELIVERY` 로 분류 (정답은 `COMPLAINT` 가 더 적합하지만 enum 에 없음).
- `ETC` 가 과도하게 광범위.

**확장 권장 (Round 후반):**
- `COMPLAINT` — REFUND 와 다른 결의 정성적 항의. 사고·라이더 태도·음식 품질 등.
- `PROMOTION` — 쿠폰·이벤트 문의. PAYMENT 와 결제 흐름이 다름.
- `TECHNICAL` (검토) — 앱 오류·결제 실패. PAYMENT 의 하위 분류로 둘지 별도 둘지 결정 필요.

## Alternatives Considered

| 대안 | 기각 사유 |
|------|----------|
| Advisor 를 모든 컨트롤러에 등록 | `StreamingChatController` 는 토큰 메타가 streaming 종료 시점에 따로 도착해서 측정 의미 약함 |
| Category 7값으로 즉시 확장 | Quest 기본값 유지 + 학습자의 도메인 결정이 평가 항목 — Round 1 단계에선 5값 유지 |
| `ETC` 제거 | catch-all 분류가 사라지면 LLM 이 강제로 잘못된 카테고리에 매핑 → 더 위험 |

## References

- 학습 회고 Q5·Q8
- README "설계 결정 Q4" + "4단계"
- 실험 D 결과 (`docs/round1/failure-observations/round1-failure-observations.md` — System Prompt 1x vs 2x 토큰 비교)
