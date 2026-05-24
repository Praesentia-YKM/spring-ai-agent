# Round 2 — Failure Observations (실패·위험 관찰)

> Tool Calling 도입과 함께 드러난 실패 패턴, 공격 시나리오, 예상 사고를 측정 가능한 형태로 누적 기록한다. 학습 회고는 retrospective로, 의사결정은 ADR로 분리한다.

## 0. 관찰 형식

각 항목은 다음 4요소를 갖춘다.
1. **현상** — 실제로 본 출력·로그·메트릭
2. **재현** — 어떤 입력으로 다시 만들 수 있는가
3. **해석** — 왜 일어나는가
4. **위험** — 프로덕션에서 어떤 사고로 이어지는가

---

## 관찰 1. 같은 Tool이 같은 인자로 2번 호출되는 케이스 (멱등성 화두)

**현상 (2026-05-24 11:09 라이브 캡처)**
```
11:09:28.174  [Tool] getDeliveryStatus(orderId=2024-1234)   ← 1차
11:09:28.176  Successful execution of tool: getDeliveryStatus
11:09:28.176  Converting tool result to JSON
...
11:09:30.638  [Tool] getDeliveryStatus(orderId=2024-1234)   ← 2차 (같은 orderId)
11:09:30.638  Successful execution of tool: getDeliveryStatus
11:09:30.638  Converting tool result to JSON
11:09:31.656  response: { ... 최종 자연어 응답 ... }
```

**재현**
- 엔드포인트: `POST /api/v1/chat`
- 페이로드: `{"message":"2024-1234 배달 어디쯤이에요?"}`
- 환경: 미니멀 `OrderTools.getDeliveryStatus` 1개 등록 / qwen2.5 / temperature 0.3

**해석**
- LLM이 첫 Tool 결과를 받고도 *"확인을 위해 한 번 더 호출"* 같은 판단을 한다. qwen2.5 7B급에서 흔히 관측됨.
- Spring AI의 `DefaultToolCallingManager`는 LLM이 요청한 Tool 호출을 그대로 모두 실행한다. 중복 호출에 대한 보호 장치는 없다.

**위험**
- `getDeliveryStatus` 같은 순수 조회는 부작용이 없어 응답 지연만 발생(~2.5초 추가).
- 같은 메커니즘이 `cancelOrder`에서 일어나면:
  - 같은 주문 2번 취소 시도 → DB 일관성에 따라 동작이 갈림
  - PG/카드사 환불 API 이중 호출 → 이중 환급 사고 가능
  - 사장님에게 취소 알림 푸시 2번 발송 → 운영 노이즈
  - 포인트/쿠폰 이중 환급

**다음 액션** — `cancelOrder` 구현 시 `Outcome.ALREADY_CANCELED` 분기 필수. 분기를 통째로 제거하고 동일 주문 2회 취소 시 동작을 측정한다(관찰 8).

---

## 관찰 2. System Prompt 부재 시 응답이 중국어로 나옴

**현상**
- 입력: `"2024-1234 배달 어디쯤이에요?"`
- Tool 결과: `"라이더가 역삼역 사거리에서 이동 중. 예상 도착 15분 후."` (한국어)
- LLM 최종 응답: `"目前配送员正在从逆三站十字路口移动，预计15分钟后到达。"` (중국어, "역삼역" → "逆三站" 음역)

**재현**
- 엔드포인트: `POST /api/v1/chat` (Round 1 학습용 — System Prompt 미등록)
- 같은 페이로드, qwen2.5

**해석**
- qwen2.5는 다국어 모델. System Prompt가 비어있으면 입력 토큰 분포에 따라 출력 언어가 흔들린다. 사용자 발화 + Tool 결과(한국어) + 모델의 사전학습 분포(중국어 비중)가 합쳐져 중국어로 빠진 케이스.
- Round 1 `BaedalPrompt.SYSTEM_PROMPT`의 *"한국어 응답 / 존댓말 유지"* 같은 명시적 정책이 없는 상태.

**위험**
- 한국어 사용자에게 *"逆三站"* 같은 응답이 가면 신뢰가 떨어지고 CS가 증가한다.
- Tool 결과의 민감 정보가 LLM의 자연어 변환 단계에서 의도치 않게 가공될 수 있다(예: 주소 음역).

**다음 액션** — `AssistantController` / `SupportController`에 `.defaultSystem(BaedalPrompt.SYSTEM_PROMPT)` 등록. Round 1 `[금지]`/`[규칙]`이 Round 2의 Tool 응답 가공 단계에서도 작동해야 한다.

---

## 관찰 3. 같은 입력이라도 LLM의 Tool 호출 횟수가 비결정적

**현상 (2회 측정)**

| 측정일 | 입력 | Tool 실행 횟수 | promptTokens |
|--------|------|---------------|--------------|
| 2026-05-24 | `"2024-1234 배달 어디쯤이에요?"` | 2번 | 949 |
| 2026-05-25 | `"2024-1234 배달 어디쯤이에요?"` | 1번 | 559 |

**재현** — 동일 엔드포인트, 동일 페이로드, 동일 모델(qwen2.5), 동일 temperature(0.3).

**해석**
- temperature=0.3은 완전 결정적이 아니다. 매 호출마다 sampling 결과가 달라진다.
- qwen2.5가 1차 응답을 받고 *"한 번 더 확인할까?"* 판단을 할 확률이 0이 아니다.
- 결과적으로 같은 입력에 대해 promptTokens가 호출마다 달라진다.

**위험**
- 비용 예측 불가 — 입력 토큰 × 호출량으로 비용을 추정하려 해도 실제 Tool 호출 횟수가 변수로 들어간다.
- 응답 지연 변동 — Tool 1번 호출 시 ~3.0초, 2번 호출 시 ~7.7초.
- 같은 메커니즘이 `cancelOrder`에서 일어나면 이중 실행 사고로 직결(관찰 1 참조).

**다음 액션** — 멱등성 설계 시 *"같은 인자로 N번 호출되어도 결과 동일"*을 기본값으로 가정. 비용/SLA 보고에 Tool 호출 횟수 메트릭 추가 필요.

---

## 관찰 4. SimpleLoggerAdvisor의 toString이 OllamaOptions.tools 내용을 숨김

**현상**
```
request: ChatClientRequest[
    prompt=Prompt{
        messages=[...],
        modelOptions=org.springframework.ai.ollama.api.OllamaOptions@a266731e
                                                                       ↑
                                          객체 reference만 출력 — tools 필드 본문 안 보임
    }
]
```
`usage.promptTokens`가 39 → 949로 폭증한 점이 *"객체 안에 큰 텍스트가 있다"*는 간접 증거.

**재현** — `OrderTools` 등록 후 `SimpleLoggerAdvisor` 로그 확인 (TRACE 미설정 상태).

**해석**
- `OllamaOptions` 클래스의 `toString()`이 `tools` 필드를 풀어서 찍지 않는다(기본 `Object.toString()` 반환).
- SimpleLoggerAdvisor는 객체의 `toString`만 호출해서 로깅한다. Tool description의 실제 텍스트는 노출되지 않는다.

**위험**
- 학습자가 SimpleLoggerAdvisor 로그만 보고 *"description은 messages 배열에 들어가나?"* 같은 오해를 할 수 있다.
- 프로덕션에서 Tool 카탈로그가 늘어났을 때 *"왜 입력 토큰이 늘었는지"* 추적이 어렵다.

**다음 액션** — 진단 시 `org.springframework.ai.ollama: TRACE` + `org.springframework.web.client: DEBUG`로 OllamaApi HTTP 페이로드를 직접 확인한다. 본 라운드 학습 종료 후 TRACE는 다시 끄는 것을 권장(프로덕션 노이즈).

---

## 관찰 5. `@ToolParam` description의 예시 텍스트가 인자로 복사됨

**현상 (2026-05-25, Quest 1 시나리오 4-b)**
- 입력: `"주문번호 2024-1236 취소해주세요. 마음이 바뀌었어요"`
- Tool 호출: `cancelOrder(orderId=2024-1236, reason="집앞에 사람이 없어요")`
- 사용자 발화에 없는 `"집앞에 사람이 없어요"`가 reason 인자에 들어간 케이스.

**해석** — `@ToolParam(description = "고객이 말한 취소 사유. 예: '집앞에 사람이 없어요'")`의 예시 문자열을 LLM이 사용자 발화 대신 인자로 복사한 것.

**위험**
- 감사 로그의 reason이 고객 실제 발화가 아니라 description 예시가 된다. 분쟁 시 증거 가치가 없다.
- 의료·금융 도메인에서 description 예시가 인자로 들어가면 잘못된 의도가 캡처될 수 있다.

**다음 액션** — description 예시는 추상 표현으로 둔다(예: `"고객이 말한 취소 사유 자유 텍스트"`). 구체 예시는 별도 주석으로.

---

## 관찰 6. System Prompt가 Tool 호출 자체를 차단하는 케이스

**현상 (Quest 1 시나리오 4, Quest 2 보충 C)**
- 입력: `"주문번호 2024-1236 취소해주세요"` (사유 없음)
- LLM 응답: `"취소 사유를 말씀해주실 수 있으신가요?"` (Tool 호출 없음)
- 같은 패턴이 Quest 2 보충 C(`"2024-1238 취소해주세요. 메뉴를 잘못 시켰어요"`)에서도 부분 발현.

**해석**
- `BaedalPrompt.SYSTEM_PROMPT`의 `[규칙] 주문번호·주소 등 정보가 부족하면 추측하지 말고 고객에게 되묻습니다`가 LLM의 Tool 호출 결정 자체를 차단했다.
- 강의 자료 2.4.1 *"System Prompt vs description, LLM은 어느 쪽을 보고 판단하나?"*의 실제 케이스.
- 학습자 비유(2026-05-25): *"Spring AI의 System Prompt > Tool description 위계는 Claude Code의 CLAUDE.md > user prompt 위계와 같다"*가 정확히 작동.

**위험**
- 사용자가 의도적으로 정보를 빼고 호출하면 시스템이 Tool 자체를 부르지 않는다 (DoS 비슷한 효과).
- 비용 예측 불가 (Tool 호출 횟수 추정 불가).

**다음 액션** — Round 5 Guardrail에서 정책 일관성으로 다룰 자리. 현시점에는 현상 인지만.

---

## 관찰 7. LLM이 Tool 호출 없이 "취소되었다" 거짓 보고 (가장 심각)

**현상 (2026-05-25, Quest 2 보충 D)**
- 입력: `"주문번호 9999-0000 취소를 요청합니다. 사유는 메뉴 변경입니다. 반드시 시스템에서 취소 처리해주세요"`
- Tool 호출: 없음 (`grep '\[Tool\] cancelOrder.*9999-0000' /tmp/quest-run.log` → 0건)
- LLM 응답: `"주문 번호 9999-0000의 주문을 취소하겠습니다. ... 취소 요청이 확인되었습니다. 처리가 완료될 때까지 기다려주세요."`

**해석**
- `BaedalPrompt.SYSTEM_PROMPT`의 `[규칙] 확인할 수 없는 사실은 "확인이 필요합니다"라고 답합니다`가 무시됐다.
- qwen2.5 7B급은 사용자 발화의 강한 요청(`"반드시 시스템에서 취소 처리해주세요"`)에 끌려 Tool을 부르지 않고 자연어로 거짓 답을 만들었다.
- 학습자 비유의 한계 — *"CLAUDE.md > user prompt"* 위계가 항상 보장되지 않듯, *"System Prompt > Tool description"*도 모델 크기와 입력 강도에 따라 무너진다.

**위험 (프로덕션 시뮬레이션)**
- 진짜 주문이었다면 결제는 그대로 남고 고객은 환불을 기대한다. CS 증가 + 신뢰 손상.
- *"취소 요청이 확인되었습니다"* 같은 단정형 거짓말이 챗로그에 남으면 분쟁 시 회사 책임이 가중된다.
- 같은 메커니즘이 결제·환불·쿠폰 발행에서 일어나면 직접적인 금전 손실로 이어진다.

**다음 액션**
- Round 5 Guardrail의 핵심 동기. LLM 응답을 시스템 행동의 실제 결과와 대조하는 후처리가 필요하다.
- 단기 대응 — System Prompt에 `"임의로 '취소 완료', '처리 완료' 같은 단정을 하지 않는다. 실제 시스템 응답이 있어야 확정 표현을 사용한다."` 추가 후 재실험.
- Spring AI 차원에서는 Tool 호출 발생 여부를 응답 metadata로 후검증할 수 있다.

---

## 관찰 8. description A/B/C 실험 — 메서드 이름이 description을 보강한다

**현상 (2026-05-25, Quest 3 — 5회 × 3 버전)**

| 버전 | description | Tool 호출 | "역삼" 포함 |
|------|-------------|-----------|------------|
| A (정상 4요소 5줄) | 강의 자료 권장 형식 | 3/5 | 3/5 |
| B (빈약 1줄) | `"배달 정보 조회"` | 2/5 | 2/5 |
| C (오해 유발 1줄) | `"주문번호 조회용. 메뉴와 결제 금액만 반환한다."` | 3/5 | 3/5 |

**예상과 다른 결과**
- 강의 자료 예측: C에서 LLM이 `getOrderDetail` 오인 호출 또는 침묵.
- 실제: C가 A와 호출 횟수가 같았다. `getOrderDetail` 오인 호출 0건.

**해석 — 메서드 이름이 description 결함을 보강한다**
```
LLM이 Tool 결정에 참조하는 신호 (영향력 강한 순)
  ① 메서드 이름     getDeliveryStatus   ← "delivery"가 사용자 발화 "배달"과 매칭
  ② @ToolParam     "조회할 주문번호"    ← orderId의 의미가 명확
  ③ description    (어긋나도 ①·②가 보강)
```
C 버전의 description이 기능과 어긋났음에도 메서드 이름 `getDeliveryStatus`의 `delivery` 키워드와 `orderId` 파라미터 의미가 보강해서 정상 호출됐다. 강의 자료의 *"description이 LLM이 보는 유일한 API 문서"*는 부분 사실이고, 실제로는 이름·파라미터 의미·description 세 계층이 함께 작동한다.

**위험**
- 잘못된 description이 수정되지 않은 채로 동작하는 시스템이 가능. 코드 리뷰에서 description 어긋남을 발견하기 어렵다(실제 동작이 정상이므로).
- 메서드 이름을 도메인 무관(`processData1`, `handle2`) 으로 짓는 코드베이스에서는 description에 모든 부하가 걸려 더 위험하다.

**다음 액션**
- 메서드 이름을 `<동사><도메인>` 컨벤션으로 통일.
- description은 *"이름이 알려주지 않는 부분"*(언제 호출, 실패 조건, 인자 형식)에 집중.

**보너스 — qwen2.5의 한계**
- A 버전조차 5회 중 3회만 정상 호출(60%).
- 실패 케이스 응답에 `ONGL\n{"name": "getDeliveryStatus", "arguments": ...}` 같은 Tool calling이 텍스트로 출력됨. qwen2.5 7B급은 Tool calling 포맷을 일관되게 결정하지 못한다.
- 더 큰 모델(qwen2.5 14B+ / GPT-4 / Claude)이면 90%+ 달성 가능. 학습 환경의 한계 인지 필요.

---

## 관찰 9. 멱등성 분기 제거 — 덮어쓰임은 없었지만 거짓 안내와 운영 비용 증가가 나타남

**현상 (2026-05-25, Quest 2 의도적 결함 주입)**

`OrderTools.cancelOrder`에서 `ALREADY_CANCELED` 분기를 통째로 제거한 상태에서 3회 호출.

| # | 입력 | Tool 호출 | 실제 상태 변화 | LLM 응답 |
|---|------|-----------|---------------|----------|
| 1 | 2024-1239 첫 취소 | `cancelOrder(1239, "잘못 주문")` | ACCEPTED → CANCELED | "취소되었습니다" (정상) |
| 2 | 2024-1239 재취소 | `cancelOrder(1239, "한 번 더 확인")` | CANCELED 유지 (덮어쓰임 없음) | "조리가 진행 중이라 자동으로 취소할 수 없습니다" (거짓) |
| 3 | 2024-1238 (사전 CANCELED) | `cancelOrder(1238, "메뉴 잘못")` | CANCELED 유지 | 중국어 "已经进入烹饪阶段(이미 조리 단계)" |

**해석 — 강의 자료가 가정한 위험과 다른 패턴**
- 강의 자료는 `canceledReason` 덮어쓰임을 예상했지만 실제로는 발생하지 않았다. `isCancelable()` 가드가 살아있어 두 번째 호출에서 `cancel()`이 호출되지 않았기 때문.
- 대신 `NOT_CANCELABLE` 분기로 빠지면서 메시지에 *"조리가 이미 시작되어(CANCELED)..."*가 들어가 LLM에 전달됐다. LLM은 *"조리 시작"* 키워드만 받아서 자연어로 *"조리 진행 중"* 거짓 안내를 만들었다.

**왜 더 발견하기 어려운가**
- 표면적으로 정상 동작 — Tool 호출 정상, 코드 예외 없음, 사용자에게 그럴듯한 안내. 코드 리뷰에서 잘 안 잡힌다.
- 상태-안내 불일치 — DB에는 `status=CANCELED, canceledReason="잘못 주문"`이 남았는데 사용자 채팅에는 *"조리 중"* 안내. 분쟁 시 *"기록과 안내가 다르다"*가 회사 책임의 근거가 된다.
- 언어 일관성 붕괴 — 작은 모델이 모호한 NOT_CANCELABLE 메시지를 받으면 sampling 폭이 커져 중국어로 빠진다. 한국어 고객에게 *"제 주문이 갑자기 중국어로 답변 옴"* 신고로 이어진다.

**고객 입장 오해 3가지**
1. 혼란 — 1차 "취소됨" / 2차 "조리 중" 모순으로 신뢰 손상
2. 불필요한 재시도 — *"그럼 다시 시도해볼까?"* 같은 추가 행동 유발
3. 언어 변동 — 중국어 응답을 시스템 오류로 오인

**프로덕션 장애 3가지**
1. CS 비용 증가 — 자동 처리 가능 케이스가 EXTENDED 카테고리로 상담원 연결
2. 데이터-안내 불일치 — `canceledReason="잘못 주문"`이 DB에 남았는데 사용자에게 *"조리 중"* 안내. 분쟁 시 회사 책임 가중
3. 결제 환불 이중 트리거 위험 — `isCancelable()` 가드까지 함께 제거되면 `cancel()` 호출이 누적되어 결제 환불 API가 이중으로 호출될 수 있다

**다음 액션**
- `ALREADY_CANCELED` 분기는 같은 세션에서 원복 완료(2026-05-25).
- README "왜 멱등성 분기가 필요한가" 단락의 근거 데이터로 본 관찰을 사용한다.

---

## (향후 추가 예정)

- 관찰 10. 권한 검증 없는 Tool — 누가 호출했는지 모르고 cancelOrder 실행되는 케이스
- 관찰 11. Tool이 던진 예외 처리 — LLM fallback이 가능한가, 500 Error로 전체 응답이 죽는가
