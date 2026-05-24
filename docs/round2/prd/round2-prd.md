# Round 2 — PRD (Product Requirements Document)

> 루퍼스 부트캠프 "Spring AI 배달 상담 에이전트" 6주 과정의 **Round 2 미션 명세**.

## 1. 목표

Round 1에 만든 "잘 답하는 챗봇" 위에 **실제 주문 데이터를 조회/변경하는 능력**을 붙인다. 이 순간부터 산출물은 챗봇이 아닌 **에이전트**가 된다.

**라운드 한 줄 메시지:** _"판단은 LLM, 실행은 Spring Bean."_
Tool을 만드는 것보다 **Tool의 경계(boundary)를 설계하는 훈련**이 본질이며, 이 한 줄이 모든 설계 결정의 기준이 된다.

## 2. 범위 (In Scope)

- `OrderTools` — `@Tool` / `@ToolParam` 으로 LLM에게 노출되는 3개 메서드 (`getOrderDetail`, `getDeliveryStatus`, `cancelOrder`)
- `OrderMockService` — `ConcurrentHashMap` 기반 Mock 주문 데이터 (4건 추가 → 총 6건)
- Tool 응답 전용 View DTO (`OrderDetailView`, `DeliveryStatusView`) — 내부 엔티티 노출 차단
- `CancelOrderResult` + `Outcome` enum 4분기 (`CANCELED`, `ALREADY_CANCELED`, `NOT_CANCELABLE`, `NOT_FOUND`) — 멱등성 표현
- `AssistantController` / `SupportController` — `.defaultTools(orderTools)` 등록
- `PerformanceLoggingAdvisor` — Round 1에서 만든 Advisor가 Tool 왕복까지 측정함을 확인
- description A/B/C 정량 실험 — 5회씩 호출하여 Tool 호출률 비교
- 멱등성 분기 제거 실험 — 의도적 결함 주입 후 실패 관찰
- AI 코드 리뷰 — AI가 짠 `@Tool` 코드의 프로덕션 결함 식별

## 3. 범위 외 (Out of Scope)

- Chat Memory / 대화 맥락 관리 — Round 3
- RAG / VectorStore — Round 4
- Guardrail / 사람 상담사 escalation — Round 5
- 실제 DB (H2/JPA) — 의도적으로 Mock 사용 (학습 주의 분산 방지)
- 분산 트랜잭션 / SAGA / Outbox — 멱등성 화두만 다루고 패턴은 다음 라운드

## 4. 학습 목표 (Quest 기준)

이번 라운드가 끝나면 다음을 할 수 있다.

- [ ] **판단과 실행의 경계**를 자기 언어로 설명할 수 있다 — 어디까지가 LLM의 일이고, 어디서부터가 Spring Bean의 일인가
- [ ] `@Tool` / `@ToolParam` 으로 주문 조회·배달 추적·주문 취소 메서드를 Tool로 등록할 수 있다
- [ ] **`description`이 LLM에게 보여주는 유일한 API 문서**라는 사실을 체감하고, 4요소(무엇/언제/입력/실패)를 적용할 수 있다
- [ ] Tool 호출 시 LLM과 서버가 몇 번 왕복하는지 로그로 관찰하고 설명할 수 있다
- [ ] 위험한 Tool(취소·결제)에 **멱등성**을 설계할 수 있고, 그 이유를 동료에게 설명할 수 있다
- [ ] Tool이 null/에러/부분 실패를 돌려줄 때 에이전트가 **Fallback 응답**을 내도록 설계할 수 있다
- [ ] 내부 도메인 엔티티를 그대로 Tool 응답으로 노출하면 안 되는 이유를 설명할 수 있다 (보안 + 토큰 경제성)

## 5. 평가 기준 (Quest 핵심 평가축)

1. **description A/B/C 정량 비교 수치** — `getDeliveryStatus`의 description을 3가지 버전(정상/빈약/오해유발)으로 바꿔가며 5회씩 호출, Tool 호출률과 응답 차이 표
2. **멱등성 분기 제거 후 ****`canceledReason`**** 덮어쓰임 + LLM 응답 인용** — `cancelOrder`의 `ALREADY_CANCELED` 분기를 통째로 제거하고 같은 주문 연속 2회 취소 요청 → 코드 동작 / LLM 응답 변화 / 고객 오해 시나리오 / 프로덕션 장애 시나리오 기록

## 6. Quest 4단계 + 공통

| 단계 | 점수 | 핵심 산출물 |
|------|------|------------|
| 1단계 | 30 | Tool 3개 구현 + Mock 4건 + 시나리오 5종 검증 + 설계 결정 문서 |
| 2단계 | (포함) | Outcome 4경로 실행 + 멱등성 분기 제거 실험 + 고객/프로덕션 시나리오 |
| 3단계 | 20 | description 3버전 × 5회 정량 비교표 + Hallucination 관찰 |
| 4단계 | (포함) | Tool 왕복 로그 4단계 + Round 1 vs Round 2 토큰 비교 + AI 코드 리뷰 |
| 공통 | 10 | "내가 배운 것 / 의문점 / Round 3 아이디어" |

## 7. 사전 준비

- Round 1 산출물(System Prompt, Structured Output, `PerformanceLoggingAdvisor`)이 동작하는 상태
- JDK 17+, Ollama qwen2.5, `curl` + `jq`
- `application.yml`의 `org.springframework.ai: DEBUG` 활성화 (Round 1에서 이미 설정됨)

## 8. 참조

- Notion Round 2 강의: https://www.notion.so/36a2e1bd53b281449fa5c1abf444072d
- Notion Round 2 Quests: https://www.notion.so/36a2e1bd53b281d9a04bf5fa4618fe63
- Notion Round 1 피드백 (Round 2 진입 점검): https://www.notion.so/36a2e1bd53b28136b486e95741115fae
- Spring AI Tools 공식 docs: https://docs.spring.io/spring-ai/reference/api/tools.html
- Spring AI 1.0.0 GA 소스: https://github.com/spring-projects/spring-ai/tree/v1.0.0
