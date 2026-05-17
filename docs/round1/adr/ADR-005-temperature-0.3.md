# ADR-005: `temperature = 0.3` 채택

- **Status:** Accepted
- **Date:** 2026-05-17
- **Round:** 1

## Context

`application.yml` 의 LLM `temperature` 값 결정. 0.0 (결정론적) / 0.3 / 0.7 (다양성 풍부) 중 선택.

## Decision

**`temperature: 0.3`** — 일관성과 자연스러움의 타협점.

## Consequences

### `temperature` 의 정확한 의미 (학습 메모)
- LLM 의 다음 토큰 분포에 적용되는 sharpness 다이얼: `softmax(logits / T)`.
- T 가 작을수록 분포가 뾰족 → 확률 1순위 토큰만 선택 가능성 ↑.
- T 가 클수록 분포가 평평 → 평소엔 안 뽑힐 토큰도 가끔 등장.
- **"지능" 다이얼이 아니다.** 모델의 정확도와 무관 — sampling 전략만 바뀐다.

### `0.0` 이 아닌 이유
- 자유 텍스트 필드(`summary`, `nextAction`) 가 매번 토씨까지 같아 **로봇 응대처럼 부자연스러움**.
- 더 중요: 잘못된 분류가 100% 일관되게 굳어져도 측정 지표상 "안정" 으로 보여 **거짓을 검출할 단서가 사라진다** (학습 회고 Q3 비판적 정정 모먼트).
- T=0 은 "정답"을 보장하지 않는다 — 단지 "모델이 가장 확신하는 답" 을 보장할 뿐. 그 확신과 진실은 별개.

### `0.7` 이 아닌 이유
- 이산값(`category`, `urgency`) 분류조차 흔들려 비즈니스 위험.
- 동일 시나리오에 매번 다른 카테고리 → 분류 후속 라우팅 불안정.

### `0.3` 의 trade-off
- ✅ 이산값(`category`/`urgency`) 일관성 거의 1.0 (실험 A/B 가 입증 — `categoryConsistency = 1.0`).
- ✅ 자유 텍스트는 응답마다 약간 변주 → 자연스러움 확보.
- ⚠️ **`categoryConsistency` 가 높은 게 정답을 의미하진 않는다** — 실험 A 에서 시나리오 2 가 5번 모두 `ORDER` 로 분류됐지만 정답은 `REFUND`. 측정 지표의 한계.

## 메타 결론

`temperature` 는 **거짓 검출 도구가 아니다.** 다양성 다이얼일 뿐이며, 부산물로 "모델이 자신 없는 시나리오" 의 흔들림이 측정 지표에 노출되어 *개발자에게* 단서를 제공한다. 검출은 결국 사람이 한다.

거짓을 진짜로 막는 건 별도의 장치:
- System Prompt 의 [금지] 규칙 (Round 1)
- Round 4 RAG (사실 grounding)
- Round 5 Guardrail (출력 검증)

## Alternatives Considered

| 대안 | 기각 사유 |
|------|----------|
| `0.0` | 자유 텍스트 다양성 0 + 거짓 검출 단서 소실 |
| `0.7` | 카테고리 분류 흔들림 → 비즈니스 위험 |
| `1.0` 이상 | 할루시네이션 증가 |

## References

- 학습 회고 Q3·Q4 (Temperature 메커니즘 + 비판적 정정)
- 강의 본문 3.1·3.2절
- 실험 A/B 결과 (`docs/round1/failure-observations/round1-failure-observations.md`)
