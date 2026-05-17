# ADR-004: `SupportResponse.refundEligibility` 필드 폐기

- **Status:** Accepted
- **Date:** 2026-05-17
- **Round:** 1

## Context

`SupportResponse` 새 필드 brainstorming 단계에서 학습자가 후보로 제시한 항목 중 `refundEligibility: ELIGIBLE / INELIGIBLE / NEEDS_REVIEW` 가 있었다. 채택 가능 여부 평가 필요.

## Decision

**`refundEligibility` 필드 폐기.** 추가하지 않는다.

## Consequences

### 폐기 사유 (정량)
- `BaedalPrompt.SYSTEM_PROMPT` 의 [규칙] 에는 이미 다음이 명시되어 있다:
  > `"금액, 보상, 환불 가능 여부를 임의로 약속하지 않습니다."`
- LLM 에게 `refundEligibility` 를 채우라고 시키면 → **시스템 프롬프트와 직접 충돌**.
- LLM 이 [규칙] 을 지키려면 매번 `INELIGIBLE` 또는 `NEEDS_REVIEW` 로 채울 수밖에 없음 → 필드 정보 가치 ≈ 0.

### Trade-off
- 환불 가능 여부는 분명히 비즈니스 핵심 정보 → **시스템이 책임져야 한다**. LLM 영역이 아니다.
- 이 결정은 Round 1 한 줄 메시지 _"LLM 은 판단, 실행은 우리 서버"_ 의 직접 적용.

### 복원 경로
- Round 2 Tool Calling 에서 **부활 예정**:
  ```java
  @Tool
  public RefundPolicy getRefundPolicy(String orderId) { ... }
  ```
- LLM 이 도구를 호출하면 결제·정책 시스템에서 실제 환불 가능 여부를 조회 → 응답에 반영.

## Alternatives Considered

| 대안 | 기각 사유 |
|------|----------|
| `refundEligibility` 그대로 추가 | [규칙] 충돌. |
| `refundReviewRequired: boolean` 로 우회 | 결국 LLM 이 판단해야 하는 영역으로 회귀 → 같은 문제 |
| Round 2 까지 보류 | 채택. |

## 메타 학습 — 새 필드 추가의 3축 검토

이 결정 과정에서 도출된 룰:
1. **이산값(enum) 인가, 자유 텍스트인가?**
2. **LLM 이 도메인 추론으로 채울 수 있는 정보인가?**
3. **시스템이 받아서 어떤 액션을 할 것인가?**

세 답이 모두 명확하지 않으면 필드 폐기.

## References

- 학습 회고 Q6 + README 설계 결정 Q2
- Round 2 Tool Calling 으로 부활 예정
