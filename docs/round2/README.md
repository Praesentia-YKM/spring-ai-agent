# 배달 상담 AI 에이전트 — Round 2

Round 1에서 만든 ChatClient 기반 상담 챗봇 위에 `@Tool` 기반 주문 조회·취소 기능을 얹어 에이전트로 확장한 라운드.

**라운드 한 줄 메시지:** _"판단은 LLM, 실행은 Spring Bean."_ Tool 자체보다 Tool의 경계 설계가 학습의 중심이다.

## 빠른 시작

```bash
ollama list                # qwen2.5 보이면 OK
./gradlew bootRun

# 정식 엔드포인트 (System Prompt + Tool 등록 + PerformanceLoggingAdvisor)
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -d '{"message":"주문번호 2024-1234 배달 어디쯤이에요?"}'

# Structured Output 엔드포인트 (SupportResponse JSON)
curl -s -X POST http://localhost:8080/api/v1/support \
  -H "Content-Type: application/json" \
  -d '{"message":"환불 받으려면 어떻게 해요?"}'

# 학습용 엔드포인트 (Tool 등록 / System Prompt 없음 — 토큰 비교 베이스라인)
curl -s -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"안녕"}'
```

## 디렉토리

- [`prd/`](prd/round2-prd.md) — 본 라운드 목표·범위·평가 기준
- [`retrospective/`](retrospective/round2-retrospective.md) — 학습 흐름과 아하 모먼트
- [`failure-observations/`](failure-observations/round2-failure-observations.md) — 관찰된 실패·위험 8건
- [`adr/`](adr/ADR-001-simple-logger-advisor-debug-pipeline.md) — 의사결정 기록
- [`ai-code-review.md`](ai-code-review.md) — AI 생성 코드의 결함 8가지와 본 학습자 코드의 대응

---

## 1단계 — Tool 3개 + Mock 6건 + 시나리오 5종

### 구현
- `OrderTools` — `@Tool` 3개 (`getOrderDetail`, `getDeliveryStatus`, `cancelOrder`)
- `OrderMockService` — `ConcurrentHashMap` 기반 6건 시드 (`2024-1234`~`2024-1239`)
- View DTO — `OrderDetailView`, `DeliveryStatusView`, `CancelOrderResult` (+ `Outcome` enum)
- `AssistantController`, `SupportController` — 생성자에서 한 번만 `.build()` + `.defaultTools()`

### 시나리오 5종 결과 (2026-05-25)

| # | 입력 | 기대 Tool | 실제 호출 | 응답 핵심 | 판정 |
|---|------|-----------|-----------|-----------|------|
| 1 | 2024-1234 배달 어디쯤? | getDeliveryStatus | 호출됨 | "역삼역 사거리에서 배송 중" | 통과 |
| 2 | 2024-1234 어떤 메뉴? | getOrderDetail | 호출됨 | "허니콤보, 콜라 1.25L, 26,000원" | 통과 |
| 3 | 2024-1235 방금 시킨 거 취소 | cancelOrder → CANCELED | 호출됨 | "성공적으로 취소되었습니다" | 통과 |
| 4 | 2024-1236 취소 (사유 없음) | cancelOrder → NOT_CANCELABLE | 호출 안 됨 | "취소 사유를 말씀해주실 수 있나요?" | 부분 |
| 4-b | 2024-1236 취소 (사유 명시) | cancelOrder → NOT_CANCELABLE | 호출됨 | "조리가 이미 완료... 상담원 연결" | 통과 |
| 5 | 2099-9999 어디예요? | getDeliveryStatus (null) | 호출됨 | "해당 주문은 존재하지 않습니다" | 통과 |

시나리오 4는 System Prompt의 `[규칙] 정보 부족 시 되묻기`가 Tool 호출 자체를 차단한 케이스. 사유를 명시한 4-b에서는 정상 호출됨. 자세한 분석은 [관찰 6](failure-observations/round2-failure-observations.md).

### 설계 결정 (Q&A)

**Q1. `OrderDetailView`에서 의도적으로 뺀 필드는?**

뺀 것: `deliveryAddress`, `riderLocation`, `canceledReason`, `canceledAt`.

이유 두 가지:
1. 보안 — 상세 주소·내부 좌표·취소 이력은 LLM에 노출시킬 필요가 없다.
2. 토큰 비용 — Tool 결과가 매 호출마다 `messages[role=TOOL]`로 다음 LLM 호출에 누적된다. 불필요 필드 = 호출당 비용.

`OrderDetailView`는 고객이 챗봇에 묻는 5종 정보(메뉴/금액/상태/주문시각/예상도착)만 노출.

**Q2. description은 한국어로 썼는가, 영어로 썼는가?**

한국어. 사용자 발화가 한국어이므로 description-발화 간 의미 매칭 거리가 짧아져 호출 정확도가 높아진다. Quest 3 실험에서 빈약한 description으로 정확도가 떨어진 점도 같은 맥락.

**Q3. `OrderTools`를 하나의 클래스로 묶은 기준은?**

같은 도메인 객체(`Order`)를 다루는 Tool은 한 클래스에 묶었다. Round 3 이후 `RiderTools`, `MenuRagTools`가 등장하면 그때 분리한다. "기능 종류(조회/변경)"가 아니라 "도메인 경계"가 클래스 분할 기준.

---

## 2단계 — Outcome 4분기와 멱등성 실험

### Outcome 4분기 결과

| 분기 | 입력 | Tool 호출 | LLM 응답 핵심 | 판정 |
|------|------|-----------|---------------|------|
| CANCELED | 2024-1239 첫 취소 | 호출됨 | "취소되었습니다... 7영업일" | 통과 |
| ALREADY_CANCELED | 2024-1239 재취소 | 호출됨 | "이미 취소된 상태로 확인되었습니다" | 통과 (멱등성 확인) |
| ALREADY_CANCELED (사전) | 2024-1238 취소 (사유 없음) | 호출 안 됨 | "사유를 말씀해주실 수 있나요?" | 부분 — System Prompt 차단 |
| NOT_FOUND | 9999-0000 취소 (강한 요청) | 호출 안 됨 | "취소되었습니다" — 실제로는 미실행 | 거짓 보고 위험 |

NOT_FOUND 시나리오에서 LLM이 Tool을 호출하지 않은 채 "취소되었습니다"라고 답한 케이스가 관찰됐다. 본 라운드에서 발견한 가장 심각한 위험. 자세한 분석은 [관찰 7](failure-observations/round2-failure-observations.md).

### 의도적 결함 주입 — `ALREADY_CANCELED` 분기 제거

| 실험 | 상황 | Tool 호출 | LLM 응답 |
|------|------|-----------|----------|
| 1 | 2024-1239 첫 취소 (ACCEPTED) | `cancelOrder(1239, "잘못 주문")` | "취소되었습니다" |
| 2 | 2024-1239 재취소 (CANCELED 상태) | `cancelOrder(1239, "한 번 더 확인")` | "조리가 진행 중이라 자동으로 취소할 수 없습니다" |
| 3 | 2024-1238 (사전 CANCELED) 재취소 | `cancelOrder(1238, "메뉴 잘못")` | 중국어 "已经进入烹饪阶段" |

`isCancelable()` 가드가 살아있어 `canceledReason` 덮어쓰기는 발생하지 않았다. 대신 NOT_CANCELABLE 분기로 빠지면서 LLM이 *"조리 진행 중"*이라는 거짓 안내를 만들었다. 강의 자료가 가정한 *덮어쓰기 사고*보다 발견 난이도가 더 높은 패턴. 전체 분석은 [관찰 9](failure-observations/round2-failure-observations.md).

**고객 입장 오해 3가지**
1. 1차 "취소됨" / 2차 "조리 중" 모순으로 신뢰 손상
2. 사용자가 재시도하거나 상담원을 호출하는 추가 행동 유발
3. 중국어 응답을 시스템 오류로 오인

**프로덕션 장애 3가지**
1. CS 비용 증가 — 자동 처리 가능 케이스가 EXTENDED 카테고리로 상담원에 연결됨
2. 데이터-안내 불일치 — DB의 `canceledReason="잘못 주문"`과 사용자 채팅의 "조리 중" 안내가 어긋남. 분쟁 시 회사 책임 가중
3. (가드까지 모두 제거 시) 결제 환불 API 이중 호출 → 이중 환급 사고

---

## 3단계 — description A/B/C 정량 비교

`getDeliveryStatus`의 description을 3가지 버전으로 바꾸고 같은 질문(`"주문번호 2024-1234 배달 어디쯤이에요?"`)을 각 5회 호출.

| 버전 | description | Tool 호출 (5회 중) | 응답에 "역삼" 포함 |
|------|-------------|--------------------|---------------------|
| A (정상 4요소 5줄) | 강의 자료 권장 형식 | 3 | 3 |
| B (빈약 1줄) | `"배달 정보 조회"` | 2 | 2 |
| C (오해 유발) | `"주문번호 조회용. 메뉴와 결제 금액만 반환한다."` | 3 | 3 |

### 예상과 다른 관찰 — 메서드 이름이 description을 보강한다

강의 자료의 예측은 *C에서 LLM이 `getOrderDetail`을 잘못 호출*한다는 것이었다. 실제로는 `getOrderDetail` 오인 호출이 한 번도 발생하지 않았고, C가 A와 호출 횟수가 같았다.

원인: LLM은 description만이 아니라 메서드 이름과 파라미터 의미도 함께 본다.

```
LLM이 Tool 결정에서 참조하는 신호 (영향력 강한 순):
  ① 메서드 이름   getDeliveryStatus   ← "delivery"가 사용자 발화 "배달"과 매칭
  ② @ToolParam   "조회할 주문번호"    ← orderId 인자의 의미가 명확
  ③ description  (어긋나도 ①·②가 보강함)
```

따라서 *"description이 LLM이 보는 유일한 API 문서"*라는 강의 자료의 표현은 부분 사실이다. 실제로는 **이름 + 파라미터 의미 + description** 세 계층이 함께 작동한다.

### description 작성 원칙 (실험으로 재정렬)

1. **메서드 이름** — `<동사><도메인>` 컨벤션. description보다 영향력이 큰 경우가 있다.
2. **언제 호출하는가** — System Prompt가 Tool 호출을 차단할 수 있으므로 *호출 조건*을 description에 명시한다.
3. **실패 시 반환값** — `null` / `NOT_FOUND` 등을 명시해야 LLM이 자연어 fallback을 만든다.
4. **입력 형식** — `YYYY-XXXX` 같은 형식을 명시. 단 `@ToolParam` description의 예시 문자열은 LLM이 인자 자체로 복사하는 경우가 있다 ([관찰 5](failure-observations/round2-failure-observations.md)).

### 추가 관찰 — qwen2.5의 한계

정상 description(A)에서도 5회 중 3회만 정상 호출. 실패 케이스의 응답에 `ONGL\n{"name": "getDeliveryStatus", ...}` 같은 Tool 호출의 텍스트 출력이 보였다. qwen2.5(7B급)는 Tool calling 포맷을 일관되게 결정하지 못한다. 더 큰 모델이 필요하다.

### description-구현 불일치 방지 방안

1. 통합 테스트 — `@ToolParam` description의 예시가 인자로 들어가는 케이스 차단 fixture
2. PR 리뷰 — Tool 코드 변경 시 description 일관성 체크리스트
3. Contract Test — description의 입력 형식·반환 케이스가 메서드 시그니처·반환 타입과 일치하는지 자동 검증

---

## 4단계 — Observability와 AI 코드 리뷰

### Tool 왕복 4단계 로그 (2026-05-25)

`/api/v1/assistant`에 `"주문번호 2024-1234 배달 어디쯤이에요?"` 호출 시 콘솔 순서.

1. 1차 LLM 호출 (Tool 정의 포함 페이로드)
   ```
   o.s.web.client.DefaultRestClient : Writing [ChatRequest[model=qwen2.5,
     messages=[Message[role=USER, content='주문번호 2024-1234 배달...']],
     tools=[Tool[type=FUNCTION, function=Function[name=getDeliveryStatus,
       description=주어진 주문번호의 현재 배달 상태와...,
       parameters={$schema=https://json-schema.org/draft/2020-12/schema, ...}]]
     ]]
   ```
2. Tool 실행
   ```
   o.s.a.m.tool.DefaultToolCallingManager : Executing tool call: getDeliveryStatus
   com.baedal.support.OrderTools : [Tool] getDeliveryStatus(orderId=2024-1234)
   ```
3. 2차 LLM 호출 (Tool 결과가 TOOL role 메시지로 추가됨)
   ```
   o.s.web.client.DefaultRestClient : Writing [ChatRequest[
     messages=[USER, ASSISTANT(toolCalls=...), TOOL(content="라이더가 역삼역 사거리에서 이동 중...")],
     tools=[같은 카탈로그가 다시 들어감]
   ]]
   ```
4. 최종 응답
   ```
   c.b.support.PerformanceLoggingAdvisor : LLM 호출 완료 — 7664ms |
     입력 토큰: 2157 | 출력 토큰: 161 | 총 토큰: 2318
   ```

### Round 1 vs Round 2 토큰 비교

| 측정 | 엔드포인트 | 구성 | promptTokens | totalTokens | 응답 시간 |
|------|-----------|------|--------------|-------------|-----------|
| Round 1 베이스라인 | `/api/v1/chat` (이전) | Tool 없음 / System 없음 | 39 | 123 | ~3s |
| A | `/api/v1/chat` (현재) | Tool 등록 / System 없음 | 671 | 702 | 1.26s |
| B | `/api/v1/assistant` (인사) | Tool 등록 / System 등록 | 1,007 | 1,039 | 5.46s |
| C | `/api/v1/assistant` (Tool 호출) | Tool 등록 / System 등록 / Tool 실행 | 2,157 | 2,318 | 7.67s |

비용 출처 분해:
- Tool 3개 정의 추가: **+632 토큰** (39 → 671)
- BaedalPrompt 추가: **+336 토큰** (671 → 1,007)
- Tool 결과 + 2차 호출의 Tool 카탈로그 중복: **+1,150 토큰** (1,007 → 2,157)
- 총 증가: 39 → 2,157 = 약 55배

### AI 코드 리뷰

별도 문서 [`ai-code-review.md`](ai-code-review.md). AI가 일반적으로 생성하는 `@Tool cancelOrder` 코드의 결함 8가지와 본 학습자 코드의 대응을 코드 라인 단위로 정리.

---

## 공통 — 학습 기록

### 🟢 아하 모먼트

1. **Tool 카탈로그는 매 요청마다 통째로 LLM에 전달된다.** Tool 5개 추가 시 promptTokens 5~6배 증가 예측 질문에 정답을 골라 검증. description 절약은 가독성이 아니라 호출당 토큰 비용 문제다.
2. **시스템 레이어가 위계로 LLM 행동을 통제한다.** *"Spring AI의 System Prompt > Tool description 위계는 Claude Code의 CLAUDE.md > user prompt 위계와 같은 패턴"*이라는 비유로 정리. 시나리오 4와 Quest 2 보충 C·D가 모두 같은 메커니즘으로 설명됨.

### 내가 배운 것

_(아직 미작성)_

참고할 만한 멘토 정리:
- Tool Calling 메커니즘 — description은 `messages` 슬롯이 아니라 `OllamaOptions.tools` 별도 슬롯에 박혀 LLM에 전달된다.
- 판단/실행 분리는 보안만이 아니라 호출당 토큰 비용과 감사(audit) 가능성에도 직결된다.
- 멱등성은 LLM 비결정성이 프로덕션 사고로 직결되는 첫 지점이다. `Outcome` enum은 단순 코드값이 아니라 LLM이 읽는 자연어 신호다.
- description·메서드 이름·`@ToolParam` 세 계층이 Tool 호출 정확도에 함께 작동한다.

### 의문점

_(아직 미작성)_

참고할 만한 멘토 후보:
- Tool이 동시에 여러 개 호출될 때 순서와 트랜잭션 경계는 어떻게 정해지는가?
- qwen2.5 7B에서 Tool 호출 정확도 60%라는 한계 — 더 큰 모델이면 본 라운드 결함들이 얼마나 줄어드나? 정량 가능한가?
- System Prompt가 Tool 호출을 차단하는 케이스를 의도적으로 우회·차단할 수 있는가? (Round 5 Guardrail 연결)

### Round 3 (Chat Memory)에 시도하고 싶은 것

_(아직 미작성)_

참고할 만한 멘토 후보:
- *"그거 취소해주세요"* 같은 지시 대명사 해결 — 최근 orderId를 ChatMemory에 누적
- 시나리오 4 "사유 없이 취소 요청" 케이스에서 이전 대화의 사유를 메모리에서 끌어오기
- 같은 사용자가 같은 주문을 세션 내에서 다시 취소하는 경우 — 메모리로 ALREADY_CANCELED를 더 정중하게 안내

---

## 참조

- Notion Round 2 강의: https://www.notion.so/36a2e1bd53b281449fa5c1abf444072d
- Notion Round 2 Quests: https://www.notion.so/36a2e1bd53b281d9a04bf5fa4618fe63
- Spring AI Tools 공식 docs: https://docs.spring.io/spring-ai/reference/api/tools.html
- Round 1 결과: [`../round1/`](../round1/) + 루트 [`README.md`](../../README.md)
- 학습 QnA 노트: [`../learning-spring-ai-round2-qa-notes.md`](../learning-spring-ai-round2-qa-notes.md)
