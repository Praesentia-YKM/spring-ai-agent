# ADR-003: `SupportResponse.estimatedResolution` — `int` 분 수치 대신 `EstimatedResolution` enum 4값

- **Status:** Accepted
- **Date:** 2026-05-17
- **Round:** 1

## Context

Quest 1단계는 `SupportResponse` 에 의미 있는 필드 1개 이상 추가를 요구한다. 강의 본문 예시는 `estimatedResolutionMinutes` (예상 해결 시간 분 수치). 그대로 채택할지, 추상화할지 결정 필요.

## Decision

**`int estimatedResolutionMinutes` 가 아니라 `EstimatedResolution` enum 4값 (`IMMEDIATE / WITHIN_30MIN / WITHIN_1DAY / EXTENDED`)** 으로 추상화.

```java
public enum EstimatedResolution {
    IMMEDIATE,        // 즉시 답변 가능 (FAQ 수준)
    WITHIN_30MIN,     // 30분 내 처리 가능 (단순 조회)
    WITHIN_1DAY,      // 1일 내 처리 (일반 취소·환불)
    EXTENDED          // 분쟁·조사가 필요한 장기 처리
}
```

## Consequences

### 긍정
- **LLM 이 도메인 추론으로 채울 수 있는 정보** — 카테고리화는 LLM 의 강점.
- **실제 분 수치 매핑은 시스템이 담당** — 정확한 SLA 데이터는 운영 시스템에 있음. "LLM 은 판단, 실행은 우리 서버" 원칙의 직접 응용.
- **이산값이므로 측정 가능** — Prompt Lab 같은 통계 측정에 잘 들어맞음.
- **enum 가능값 제약** — JSON Schema 가 LLM 에게 4값 외 답을 못 주게 강제.

### 부정 / Trade-off
- **정밀도 손실** — "약 45분" 같은 구체 수치 표현 불가.
- **enum 값 정의의 도메인 결정 부담** — 4값 vs 5값·6값 같은 카테고리 분할 정책 결정 필요.
- **시스템 매핑 테이블이 별도 필요** — Round 후반에 enum → 실제 분 수치 매핑 Tool 또는 DB 가 필요.

## Alternatives Considered

| 대안 | 기각 사유 |
|------|----------|
| `int estimatedResolutionMinutes` | **LLM 이 실제 SLA 분 수치를 모름** → 그럴듯한 가짜 숫자를 만들어낼 위험 (hallucination). 학습 회고 Q6 의 hallucination 경향 관찰과 같은 결. |
| 문자열 자유 텍스트 (예: `"약 1시간"`) | 일관성 측정 불가, 시스템 라우팅 불가. |
| `refundEligibility: ELIGIBLE/INELIGIBLE` | ADR-004 에서 별도 폐기 결정. |

## References

- 학습 회고 Q6 (1단계 실행 결과 + hallucination 경향)
- README "설계 결정 문서 Q1"
