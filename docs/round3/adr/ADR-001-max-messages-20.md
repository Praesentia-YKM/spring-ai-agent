# ADR-001 — `maxMessages = 20`

## Status
Accepted

## Context
`MessageWindowChatMemory`는 세션당 최근 N개 메시지만 유지한다. N을 정해야 한다. 너무 작으면 지시 대명사 해결이 깨지고, 너무 크면 입력 토큰이 선형 증가해 비용·지연이 커진다.

배달 상담의 평균 대화 길이를 **3~6턴**으로 가정한다. 1턴 = USER 1개 + ASSISTANT 1개 = **2 메시지**. (Tool 메시지는 `MessageChatMemoryAdvisor`가 저장하지 않으므로 계산에서 제외.)

- 3~6턴 = 6~12 메시지.
- `maxMessages = 20` ≈ **약 10턴** 분량 → 평균 대화를 여유 있게 커버하고, 한두 번 길어져도 맥락 유지.

## Decision
`maxMessages = 20`을 기본값으로 한다. 단 실험을 위해 `baedal.memory.max-messages` 프로퍼티로 노출해 재컴파일 없이 바꿀 수 있게 한다.

## Consequences — 2단계 실측 근거
| maxMessages | 평균 입력 토큰 | 지시대명사 해결(5턴 중) | 비고 |
|---|---|---|---|
| **2** | ~1,435 (평탄) | **2/5** | 직전 한 쌍만 남아 t7 "그거 취소"·t9 "그 주문"에서 붕괴 |
| **20** | ~1,910 (선형↑) | 4/5 | 10턴 내 누적, turn10 입력 2,407에서 평탄화 시작 |
| **MAX_VALUE** | ~2,063 (선형↑) | 4/5 | 10턴에선 20과 거의 동일 (10턴=최대 20메시지라 윈도우 미발동) |

- **20과 MAX_VALUE가 10턴에선 사실상 동일**하다 — 차이는 20번째 메시지(=11턴째)부터 발생. 즉 maxMessages의 효과는 "긴 대화"에서만 드러난다.
- 2는 토큰은 가장 싸지만(평탄 ~1,300) 상담 에이전트로서 실격: "그거"를 못 푼다.
- 따라서 20은 **"평균 대화를 커버하면서 토큰 상한을 두는"** 균형점. 더 긴 상담(환불 분쟁 등)이 흔하면 30~40으로 올리되, 토큰 비용을 함께 모니터링한다.

근거 원본: [raw/scenarios.md §2단계](../raw/scenarios.md), [round3-failure-observations 관찰 3](../failure-observations/round3-failure-observations.md).
