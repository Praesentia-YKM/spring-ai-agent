# 배달 상담 AI 에이전트 — Round 3

Round 2의 Tool Calling 에이전트 위에 **대화 메모리(Chat Memory)** 를 얹어, "그거 취소해줘" 같은 지시 대명사를 해결하는 라운드.

**라운드 한 줄 메시지:** _"LLM은 매 호출이 독립인 기억상실증 환자다."_ 기억은 모델 안이 아니라 **서버 바깥**에 두고 매 호출마다 프롬프트에 다시 끼워 넣는다. 그 경계(크기/저장소/세션)를 설계하는 게 학습의 중심.

## 3레이어 한눈에
```
⑤ MessageChatMemoryAdvisor (흐름·order 10)  →  before: get() 과거 주입 / after: add() 저장
      └ 품음 ④ MessageWindowChatMemory (최근 20개)
              └ 품음 ③ ChatMemoryRepository (InMemory ↔ JDBC)
                       └ Map<conversationId, List<Message>>
```
합성(has-a) 구조라 ③만 갈아끼우면 InMemory↔JDBC 전환. ([ChatMemoryConfig.java](../../src/main/java/com/baedal/support/ChatMemoryConfig.java))

## 빠른 시작
```bash
ollama list                # qwen2.5
./gradlew bootRun          # 기본 InMemory

# 1) 주문번호 언급
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" -H "X-Session-Id: demo" \
  -d '{"message":"2024-1234 어디쯤 있어요?"}'
# 2) "그거" — 지시 대명사 (같은 세션)
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" -H "X-Session-Id: demo" \
  -d '{"message":"그거 언제 도착해요?"}'
# 3) Memory 상태 / 세션 목록 / 삭제
curl -s http://localhost:8080/api/v1/session/demo/messages | jq
curl -s http://localhost:8080/api/v1/session/ids | jq
curl -s -X DELETE http://localhost:8080/api/v1/session/demo

# JDBC 프로필 (3단계): build.gradle의 JDBC 의존성 2줄 주석 해제 후
./gradlew bootRun --args='--spring.profiles.active=jdbc'
```
> 환경: Spring Boot 3.4.1 / Spring AI 1.0.0 / JDK 17(toolchain) / Ollama qwen2.5. 모든 결과는 2026-05-31 실측.

## 디렉토리
- [`prd/`](prd/round3-prd.md) — 목표·범위·평가 기준
- [`adr/`](adr/) — 설계 결정 5건 (maxMessages / 세션식별 / InMemory↔JDBC / Advisor order / Memory≠감사)
- [`failure-observations/`](failure-observations/round3-failure-observations.md) — 실패·위험 9건
- [`raw/`](raw/scenarios.md) — 응답 본문·Memory JSON·DEBUG 원본
- [`ai-code-review.md`](ai-code-review.md) — AI 생성 메모리 코드의 결함 4건
- [`retrospective/`](retrospective/round3-retrospective.md) — 학습 기록

---

## 1단계 — 3레이어 + X-Session-Id + 시나리오 5종

### 구현
- `ChatMemoryConfig` — 3 Bean(`InMemoryChatMemoryRepository` / `MessageWindowChatMemory(20)` / `MessageChatMemoryAdvisor.order(10)`), `@Profile("!jdbc")`, `maxMessages` 프로퍼티화
- `SessionController` — `GET /{id}/messages`, `DELETE /{id}`, `GET /ids`
- `AssistantController`·`SupportController` — `@RequestHeader X-Session-Id` + `.defaultAdvisors(memoryAdvisor, performanceAdvisor)` + `.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))`
- `BaedalPrompt` — `[대화 맥락 사용 규칙]` 섹션 추가

### 시나리오 5종 결과 (실측)
| # | 시나리오 | 기대 | 실제 | 판정 |
|---|---|---|---|---|
| 1 | s1: "1234 어디쯤?" → "그거 언제 도착?" | 그거=1234 | 2회차가 1234 도착시간 안내 | ✅ |
| 2 | s2: "1234 취소" → "그거 말고 1235 취소" | 1234→1235 전환 | 대상 1235로 전환(단 전부 중국어·사유 되묻기) | ✅ 전환 / ⚠️ |
| 3 | s3: "아까 물어본 그 주문 언제?" | 이전 orderId 추출 | "그 주문"=1234 도착시간 | ✅ |
| 4 | s4a "1234..." → s4b "그 주문 어디?" | B에 맥락 없음 | s4b가 주문번호 되물음, Memory[s4b]에 s4a 없음 | ✅ |
| 5 | s5 1234 언급 → DELETE → "그거" | 맥락 소멸 | DELETE 후 Memory `[]`, "그거" 해석 불가 | ✅ |

`/session/ids` = `["s3","s4b","s4a","s5","s1","s2"]` — 6세션 분리 저장. 원본·DEBUG: [raw §1단계](raw/scenarios.md). 설계 근거: [ADR-002](adr/ADR-002-session-id-header.md).

---

## 2단계 — maxMessages 20 / 2 / MAX 정량 비교

| 실험 | maxMessages | 평균 입력 | 지시대명사 해결(5턴 중) | 입력 토큰 추세 |
|---|---|---|---|---|
| A | 20 | ~1,910 | 4/5 | turn2 1356 → turn10 2407 **선형↑** |
| B | 2 | ~1,435 | **2/5** | ~1,300 **평탄** (누적 안 됨) |
| C | MAX_VALUE | ~2,063 | 4/5 | A와 동일(10턴=20메시지라 미발동) |

- **B=2는 turn7 "그거 취소"·turn9 "그 주문"에서 붕괴** → "정확한 주문번호를 알려달라". 요약(turn10)도 1235·앞부분 소실.
- **A≈C**: 차이는 11턴째(20메시지 초과)부터. 10턴에선 둘 다 누적.
- 토큰표·실패 캡처: [raw §2단계](raw/scenarios.md). 근거: [ADR-001](adr/ADR-001-max-messages-20.md), [관찰 3](failure-observations/round3-failure-observations.md).

---

## 3단계 — InMemory vs JDBC

### 재시작 후 유지
| 저장소 | 유지? | 비고 |
|---|---|---|
| InMemory | ❌ | JVM 종료 시 소멸 |
| jdbc:h2:**mem** | ❌ | 재시작 후 `/session/ids`=`[]` |
| jdbc:h2:**file** | ✅ | 재시작 후 1234 맥락 생존 |

### 의사결정 트리 (요약)
- **InMemory 충분**: 단일 인스턴스 + 짧은 세션 + 감사 불필요.
- **JDBC 필요**: 멀티 인스턴스(LB) / 재시작 연속성 / 감사·법적 보관.
- 배달 실운영 → **PostgreSQL**(Round 4 PgVector와 인프라 통합).

> ⚠️ 실측 발견: Spring AI 1.0.0은 **schema-h2.sql 미동봉** → `platform=postgresql` 강제 필요([관찰 4](failure-observations/round3-failure-observations.md)). 또한 윈도우+JDBC는 세션당 최근 N개만 남으므로 **감사 로그가 아니다**([ADR-005](adr/ADR-005-memory-is-not-audit-log.md)). 전체 트리: [ADR-003](adr/ADR-003-inmemory-vs-jdbc.md), [raw §3단계](raw/scenarios.md).

---

## 4단계 — Observability + AI 코드 리뷰

### 턴별 입력 토큰 (실험 A, maxMessages=20)
| turn | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| 입력 토큰 | 2592 | 1356 | 1448 | 1542 | 1668 | 1858 | 1940 | 2075 | 2211 | 2407 |

- turn2(1356) → turn10(2407) = **약 1.8배**. 증가분은 **Memory에 누적된 이전 메시지**. (turn1 2592는 Tool 카탈로그+결과가 포함된 호출.)
- 비용 출처 두 갈래: ① Memory 누적(턴마다 ~+130) ② Tool 호출 시 카탈로그(~+1,100 고정).

### Memory 주입 증거 (2회차 프롬프트)
2회차 요청 `messages[]`가 `[1회차 USER, 1회차 ASSISTANT, SYSTEM, 새 USER]`로 시작 — Memory가 조립 시점에 과거를 끼워 넣음 확인. **SystemMessage가 과거 뒤에 위치**하는 관찰도 포착([ADR-004](adr/ADR-004-memory-advisor-order.md), [raw §1단계](raw/scenarios.md)).

### AI 코드 리뷰
"세션별 대화 유지" 요청에 AI가 만든 `HashMap` 직접 관리 코드의 결함 4건(크기 무제한 / thread-unsafe / 재시작 소실 / 프라이버시)과 Round 3 방식 개선: [ai-code-review.md](ai-code-review.md).

---

## 공통 — 학습 기록
"내가 배운 것 / 의문점 / Round 4 아이디어": [retrospective](retrospective/round3-retrospective.md).

## 참조
- Spring AI Chat Memory: https://docs.spring.io/spring-ai/reference/api/chat-memory.html
- saveAll 트랜잭션 이슈: https://github.com/spring-projects/spring-ai/issues/3153
- Round 2: [`../round2/`](../round2/README.md)
