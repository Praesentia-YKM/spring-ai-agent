# round4-03 · Advisor 체인 통합 — Memory + RAG 협업

> **사전 완성(레퍼런스)**: ① `RagConfig` ✅  ② `KnowledgeLoader`(적재+중복방지) ✅
> **이 worktree의 초점 기능 ③**: `AssistantController` / `SupportController`의 TODO G·H —
> `ragAdvisor`를 Advisor 체인에 끼워 RAG 검색 결과가 프롬프트에 주입되게 한다.

## 0. 사전 요건

```bash
docker compose up -d
ollama pull qwen2.5 && ollama pull qwen3-embedding:0.6b
```

## 1. 학습 포인트 (개념)

- **Advisor = 필터 체인.** 각 Advisor는 한 가지 일만 하고, 체인이 조립한다. 기능 추가 = 새 Advisor 끼우기.
- **순서가 핵심** (`order` 낮을수록 먼저):
  - `MessageChatMemoryAdvisor(10)` — 이전 대화 이력 주입 ("아까 그 주문"의 orderId 복원)
  - `QuestionAnswerAdvisor(20)` — RAG 검색 결과 `Context:` 주입
  - `PerformanceLoggingAdvisor(100)` — 최종 호출 시간/토큰 로깅
- **왜 Memory가 먼저인가** — Memory가 "아까 그 주문"을 `2024-1234`로 복원한 *뒤*에 RAG가 그 복원된 질문을 임베딩·검색해야 "그 주문의 환불 정책"을 찾는다. 순서가 뒤집히면 복원 전 원문("아까 그 주문 환불 돼요?")으로 검색되어 엉뚱한 정책이 Top-K에 오른다.
- **빌더 누적 함정(Round 2)** — 핸들러마다 `.defaultAdvisors()` 호출 금지. 생성자에서 1회 build.

## 2. 초점 TODO (컨트롤러 2곳)

| TODO | 파일 | 내용 |
|------|------|------|
| G | `AssistantController` | `.defaultAdvisors(memoryAdvisor, ragAdvisor, performanceAdvisor)` |
| H | `SupportController` | 동일 — 두 엔드포인트가 같은 지식·맥락을 공유해야 일관됨 |

### 구현 힌트

```java
// 두 컨트롤러 모두 생성자에서:
this.chatClient = builder
        .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
        .defaultAdvisors(memoryAdvisor, ragAdvisor, performanceAdvisor)  // ← ragAdvisor 추가
        .defaultTools(orderTools)
        .build();
```

> ⚠️ `defaultAdvisors(...)` 나열 순서는 가독성일 뿐 — 실제 실행 순서는 `getOrder()`가 결정한다.
> 그래도 관례상 order 오름차순으로 나열한다. (ragAdvisor의 order=20은 ①에서 설정됨)
> 흔한 실수: 한 컨트롤러에만 달기 → 두 곳 모두 필요.

## 3. 설계 결정 질문 (README)

- 왜 `memory(10) → rag(20) → performance(100)` 순서인가? "아까 그 주문"을 예로 **프롬프트 조립 순서** 관점에서.
- 반대 순서가 더 나은 상황이 존재하나? (예: Memory에 개인정보가 있어 임베딩하면 안 되는 경우 → Round 5 Guardrail 예고)
- (실험은 다음 단계 이후) order(20)→order(5)로 뒤집으면 2턴 대화에서 무엇이 깨지나?

## 4. 검증 (직접 확인)

```bash
./gradlew bootRun
# 기동 후: 신규 7건 적재 확인 (②가 이미 완성됨)

# (1) RAG 단독 — 정책 원문이 응답에 나와야
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" -H "X-Session-Id: rag-1" \
  -d '{"message":"비 오는 날 배달이 늦으면 보상 받을 수 있나요?"}'

# (2) Memory+RAG 협업 (2턴)
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" -H "X-Session-Id: memo-rag" \
  -d '{"message":"주문번호 2024-1234 배달 어디쯤이에요?"}'
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" -H "X-Session-Id: memo-rag" \
  -d '{"message":"아까 그 주문 환불 돼요?"}'
```

**DEBUG 로그 관찰 포인트** (`org.springframework.ai: DEBUG`):
- `QuestionAnswerAdvisor`가 검색을 수행했는가
- 프롬프트에 주입된 `Context:` 블록 — 정책 원문 일부가 보임
- 2턴에서 Memory가 `2024-1234`를 복원했고, RAG가 환불 정책을 주입했는가

## 5. 자가 점검 체크리스트

- [ ] **두 컨트롤러 모두** `defaultAdvisors(memory, rag, performance)` (grep으로 확인)
- [ ] 응답에 정책 원문 수치 포함 (예: "기상 특보", "+30분")
- [ ] DEBUG 로그에 `Context:` 블록이 보임
- [ ] 2턴 대화에서 "아까 그 주문"이 1234로 복원되어 환불 정책 검색에 사용됨
- [ ] `SupportController`(structured) 응답도 정책 근거 반영

## 6. 다음 단계

③ 완료 후 → `round4-04-policy-prompt` worktree. **①②③ 레퍼런스 완성**, 초점은 ④ `[정책 인용 규칙]`(환각 방지)이다.
