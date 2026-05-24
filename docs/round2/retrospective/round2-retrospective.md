# Round 2 — Retrospective (학습 회고)

> 학습이 진행되는 동안 발견과 아하 모먼트를 누적 기록한 라이브 문서. 의사결정은 ADR로, 실패 관찰은 failure-observations로 분리한다.

## 0. 학습 진행 메타

- 학습 시작: 2026-05-24
- 진행 형식: `/learning` 멘토 세션 + Round 2 강의 자료(Notion) + Quest 풀이
- 환경: Spring AI 1.0.0 GA / Spring Boot 3.4.1 / Java 17 / Ollama qwen2.5
- QnA 노트: [`docs/learning-spring-ai-round2-qa-notes.md`](../../learning-spring-ai-round2-qa-notes.md)

---

## 1. 학습 흐름 (시간순)

### 1-1. 큰 그림 — Tool Calling이 무엇인가

- 한 줄 비유: 콜센터 상담원(LLM)에게 사내 시스템 접근 코드를 발급한다. 단, 상담원이 시스템을 직접 조작하지 않고 "이 버튼 눌러줘"라고 요청만 한다.
- 핵심 화두: `ChatClient.call()` 한 번의 호출 안에서 LLM과 Spring Bean(@Tool) 사이의 N번 왕복을 가시화하는 것.

### 1-2. 가지 1번 — "LLM은 OrderTools의 존재를 어떻게 아는가?"

- 양자택일: (A) 미리 학습되어 있음 / (B) 매 요청마다 프롬프트에 포함됨 / (C) LLM이 별도 API로 조회
- 학습자 첫 답: A
- 정정 근거 두 가지:
  1. 시간 모순 — `OrderTools`는 어제 자정에 만든 클래스. LLM 사전학습 시점보다 늦다.
  2. DEBUG 로그 실제 캡처 — 1-3에서 본인 어플에서 확인.
- 결론: B (매 요청마다 프롬프트의 별도 슬롯에 포함되어 전달됨).

### 1-3. SimpleLoggerAdvisor로 베이스라인 캡처

- 발견 1: `application.yml`에 `org.springframework.ai: DEBUG`만으로는 프롬프트 본문이 콘솔에 나오지 않는다. Spring AI 1.0 GA는 `SimpleLoggerAdvisor`를 명시적으로 등록해야 하는 옵트인 설계.
- 발견 2: Round 1 ChatController에 advisor 한 줄을 추가하니(`.advisors(new SimpleLoggerAdvisor())`) request/response 한 묶음이 가시화됐다.
- 베이스라인 측정 (Tool 미등록):
  ```
  messages         = [UserMessage 1개]
  modelOptions     = OllamaOptions@... (객체 ID만 보임)
  toolCalls        = []
  promptTokens     = 39
  completionTokens = 84
  ```
- 학습자가 보낸 `"배달어 디까지 와있어"`에 LLM은 `"정보를 제공받지 못했습니다"`로 답함. hallucinate하지 않은 것이 운이 좋았던 케이스 (Round 1 `[금지]` 학습의 필요성을 다시 확인).

### 1-4. 호출 사슬 추적 — Spring AI가 Ollama까지 가는 경로

- `ChatController` 12줄 어디에도 `OllamaChatModel`이 없는데, `spring-ai-starter-model-ollama` 의존성 한 줄로 `ChatClient.Builder` 빈이 자동 등록되어 학습자에게 주입됐다.
- 학습자 의문: *"OllamaChatModel.call()을 호출하는 클라이언트 코드는 어디 있나?"*
- 경로: `DefaultChatClient.DefaultChatClientRequestSpec.call()` → `buildAdvisorChain()` → `ChatModelCallAdvisor` (Spring AI가 체인 끝에 자동으로 추가하는 종착 advisor) → `chatModel.call(prompt)` → `OllamaChatModel.call()` → `OllamaApi.chat()` → HTTP POST `localhost:11434/api/chat`.
- Round 1 학습과의 연결: SimpleLoggerAdvisor는 advisor 체인의 한 칸일 뿐이고, 체인의 마지막 칸이 실제 LLM 호출의 종착. Round 1의 PerformanceLoggingAdvisor도 같은 체인의 한 칸.

### 1-5. 미니멀 OrderTools 1개 등록 — Tool 등록 후 비교

- 임시 학습용 Tool: `OrderTools.getDeliveryStatus(orderId)` 하나만. Mock은 `if` 한 줄(`"2024-1234"`만 라이더 위치 반환).
- ChatController에 `.tools(orderTools)` 한 줄 추가 (`.defaultTools()` 대신 — 강의 자료 2.5.1의 빌더 누적 함정 회피).
- 같은 curl로 비교:
  ```
                      베이스라인    Tool 등록 후     변화
  promptTokens        39            949              +910 (약 24배)
  messages            User 1개      User 1개         같음
  modelOptions        @c186c016     @a266731e        객체 ID만 — 본문 안 보임
  toolCalls (응답)    []            []               최종 응답 기준 같음
  Tool 실행 횟수      0번           2번               +2 (멱등성 화두 등장)
  응답 언어           한국어         중국어            달라짐
  ```
- 결론: description은 `messages` 배열이 아니라 `OllamaOptions.tools` 슬롯에 들어가 Ollama HTTP API에 별도 슬롯으로 전달된다. SimpleLoggerAdvisor는 객체 `toString`만 호출해서 본문을 보여주지 못했고, promptTokens가 39→949로 폭증한 점이 간접 증거가 됐다.

### 1-6. 실제 JSON Schema 페이로드 가시화 (2026-05-25)

- `application.yml`에 `org.springframework.web.client: DEBUG` 추가 → `DefaultRestClient`의 `Writing [ChatRequest[...]] as application/json` 라인이 OllamaApi가 보내는 실제 HTTP 페이로드의 `ChatRequest` 객체를 그대로 보여준다.
- 학습자가 `OrderTools.java`에 쓴 한국어 description이 페이로드의 `tools[0].function.description` 슬롯에 그대로 들어간 것을 직접 확인.
- `@ToolParam(description=...)`의 한국어는 `tools[0].function.parameters.properties.orderId.description`으로 매핑됐다.
- JSON Schema 표준: Draft 2020-12 (`$schema=https://json-schema.org/draft/2020-12/schema`).
- 2차 LLM 호출 페이로드도 캡처. Tool 결과는 `Message[role=TOOL, content=...]`로 변환되어 `messages` 배열에 추가됐고, `tools` 슬롯도 매 호출마다 다시 들어갔다. 토큰 비용이 호출 횟수에 비례 누적됨을 확인.
- 어제 측정 949 vs 오늘 559 차이는 LLM의 Tool 호출 횟수 차이(어제 2회 / 오늘 1회)에서 나온 것. 멱등성 화두 보강.

### 1-7. 가지 1번 종료 — 학습자 이해 검증 통과 (2026-05-25)

- 검증 질문: *"Tool 5개 더 추가하면 promptTokens는?"* → 학습자 답 B (5~6배 증가) = 정답.
- 의미: Tool 카탈로그가 매 요청마다 통째로 전달된다는 메커니즘이 직관으로 자리잡았다. Round 2 강의 4부 "Tool 정의의 비용"의 핵심.

---

## 2. 아하 모먼트 (🟢)

> 학습자가 직접 한 줄로 정리한 발견만 마킹한다. 멘토가 떨어뜨린 결론은 마킹하지 않는다.

- 🟢 **Tool 카탈로그는 매 요청마다 통째로 LLM에 전달된다.** Tool 5개 추가 시 promptTokens 5~6배 증가 양자택일 정답으로 검증(2026-05-25). description 절약은 가독성이 아니라 호출당 토큰 비용 문제다.
- 🟢 **시스템 레이어가 위계로 LLM 행동을 통제한다.** 학습자 비유: *"Spring AI의 System Prompt > Tool description 위계는 Claude Code의 CLAUDE.md > user prompt 위계와 같은 패턴."* 시나리오 4(사유 미명시 → Tool 차단)와 Quest 2 보충 C·D(Tool 안 부르고 답)가 모두 같은 메커니즘으로 설명됨.

---

## 3. 잘 흡수된 부분 / 부족한 부분

### 잘 흡수
- 호출 사슬 추적 — 학습자 코드부터 `DefaultChatClient` → `ChatModelCallAdvisor` → `OllamaChatModel` → HTTP까지 코드 라인 단위로 짚을수록 빠르게 흡수됨.
- 베이스라인 vs Tool 등록 후 비교 — 표 형식이 효과적이었다.

### 부족 / 다음에 강화
- 멱등성 분기 제거 실험 후 *덮어쓰임이 발생하지 않은 이유*를 사전에 예측 못 했다. `isCancelable()` 가드와 `ALREADY_CANCELED` 분기의 역할 분리를 다음 라운드에서 다시 짚을 필요.
- description A/B/C 실험에서 *메서드 이름이 description을 보강한다*는 발견은 강의 자료에 없었다. 다음 라운드부터는 *"description 외에 LLM이 보는 다른 신호는?"*을 학습 초반에 미리 정리하면 좋겠다.

---

## 4. 다음 단계로 가져갈 것

- 가지 2번 — `@Tool description` 작성 원칙 (강의 자료 2.4). 3-A/B/C 실험 전 4요소(무엇/언제/입력/실패)로 본인 description 재점검.
- 가지 3번 — 판단/실행 분리. Tool이 null 반환 시 LLM의 fallback 행동 시나리오.
- 가지 4번 — 멱등성. 학습자가 *"Tool이 2번 호출됨"*을 본인 로그로 이미 체감. 강의 자료 3부의 Outcome 4분기 설계로 자연스럽게 진입.

---

## 5. Round 1과 비교

| 항목 | Round 1 | Round 2 |
|------|---------|---------|
| Advisor 학습 | PerformanceLoggingAdvisor 직접 작성 | SimpleLoggerAdvisor 옵트인 + 체인 종착 ChatModelCallAdvisor 추적 |
| 토큰 감각 | Structured Output 시 schema 자동 주입 (소폭 증가) | Tool 등록 시 `OllamaOptions.tools`에 description 누적 (대폭 증가, 24~55배 측정) |
| 위험 인식 | `[금지]` 제거 시 hallucinate 가능성 | Tool 호출 중복 시 결제/취소 이중 실행 가능성 + Tool 호출 없이 거짓 보고 |
| 실패 관찰 매체 | DEBUG 로그 + 응답 비교 | 동일 + TRACE + Tool 실행 로그 + Ollama HTTP 페이로드 직접 캡처 |

---

## 6. Quest 완료 후 작성 — "내가 배운 것 / 의문점 / Round 3 아이디어"

해당 섹션은 [`docs/round2/README.md`](../README.md#공통--학습-기록) 의 "공통 — 학습 기록"으로 이관됐다. 학습자 본인 표현은 그쪽에 작성한다.
