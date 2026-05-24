# ADR-001: 학습용 디버그 파이프라인 — SimpleLoggerAdvisor + TRACE 로깅 + 미니멀 OrderTools

- **Status:** Accepted
- **Date:** 2026-05-24
- **Round:** 2

## Context

Round 2 학습 진입 시점에 학습자(Spring AI 비숙련)가 *"Tool Calling이 일어났을 때 LLM과 서버 사이에서 실제로 무엇이 오고가는가"* 를 본인 눈으로 검증해야 했다.

기본 상태(`application.yml`에 `org.springframework.ai: DEBUG` 만 설정)에서는 다음이 불가:
- ChatClient 요청/응답 본문이 콘솔에 찍히지 않음 (Spring AI 1.0 GA는 옵트인 설계)
- Tool description이 어디로 주입되는지 보이지 않음
- LLM ⇄ Tool 왕복 횟수가 보이지 않음

본 라운드의 Quest는 위 항목을 *측정 가능한 수치*로 비교(베이스라인 vs Tool 등록 후) 해야 하므로, 학습 초기 단계에 **재현 가능한 관찰 파이프라인**이 필요했다.

## Decision

학습 초기에 다음 3단계의 디버그 파이프라인을 ChatController에 적용:

1. **`SimpleLoggerAdvisor`** 를 `ChatController`의 `.advisors(...)` 에 등록 — request/response 본문 가시화
2. **`application.yml` 로깅 레벨 강화** — Tool 변환 단계 + Ollama HTTP 페이로드 가시화
   ```yaml
   logging:
     level:
       org.springframework.ai: DEBUG
       org.springframework.ai.ollama: TRACE
       org.springframework.ai.model.tool: TRACE
       org.springframework.web.client: DEBUG
   ```
3. **미니멀 ****`OrderTools`**** 1개** 만 먼저 등록 (`getDeliveryStatus` 단일 메서드, hardcoded `if`) — Quest 본격 구현 전 *베이스라인 ↔ Tool 등록 후* 비교 측정용

## Consequences

### 긍정
- 학습자가 *프롬프트 본문 / Tool description / 토큰 폭증 / Tool 실행 횟수* 를 **본인 로그로 직접 확인** — 강의 자료의 추상 설명에서 멈추지 않음.
- 베이스라인 `promptTokens=39` 와 Tool 등록 후 `promptTokens=949` 의 **24배 차이를 수치로 박을 수 있음** → Round 2 Quest 4단계의 토큰 비교표 근거 데이터로 그대로 사용.
- Tool 2번 호출(`getDeliveryStatus(orderId=2024-1234)` × 2) 같은 **멱등성 화두를 학습 초반에 자연스럽게 노출** → Quest 2단계(멱등성 실험)로 동기 부여.
- `ChatController`(Round 1 학습용 엔드포인트)를 *디버그 전용*으로 활용 → `SupportController`/`AssistantController`(프로덕션 형태 엔드포인트)는 깨끗하게 유지.

### 부정 / Trade-off
- TRACE 로깅이 **콘솔 노이즈를 폭증**시킴 — 학습 외 작업 시 거슬림. 본 라운드 종료 후 TRACE는 원복 권장 (재현 필요 시 다시 켜기).
- `SimpleLoggerAdvisor`는 `OllamaOptions`의 `toString` 한계로 **`tools` 필드 내용이 객체 reference로만 노출** → 학습자에게 *"왜 description이 안 보이지?"* 라는 혼란 일시 발생. 보강 수단으로 `org.springframework.ai.ollama: TRACE` 동시 활성화 필요.
- 미니멀 `OrderTools` 는 Quest 정식 OrderTools(3 Tools + Mock 6건 + View DTO)로 **재작성 필요** — 학습 임시 코드와 Quest 산출물이 다름. 재작성 시 `ChatController`도 다시 정리해야 함.

## Alternatives Considered

| 대안 | 기각 사유 |
|------|----------|
| **공식 docs만 읽고 진행** | 학습자 비숙련. 추상 설명만으로 *"실제로 무엇이 일어나는지"* 체감 불가. 강의 자료의 *"DEBUG 로그에서 ****`ToolResponseMessage`**** 검색"* 권장 사항과도 일치. |
| **첫 Tool부터 Quest 정식 ****`OrderTools`**** 3개 + Mock 6건** | 학습 초기에 작업량이 너무 많아 베이스라인 비교(`promptTokens=39 vs N`) 의 *순수한 단일 변수 실험*이 흐려짐. 한 번에 너무 많이 바꾸면 *무엇이 토큰 폭증의 원인인지* 못 짚음. |
| **`OLLAMA_DEBUG=1`**** 로 Ollama 서버 측 로그만 보기** | macOS Ollama 데스크탑 앱 설정 의존도 ↑. 학습자 환경 차이로 재현성 ↓. Spring AI 차원에서 가시화하는 쪽이 학습 도메인(Spring AI)에 집중. |
| **Wireshark / mitmproxy 로 HTTP 캡처** | 학습 도구 진입 장벽 ↑. 본 학습의 주제(Spring AI Tool Calling)와 무관한 영역으로 주의 분산. |

## References

- 본 학습의 QnA 노트: [`docs/learning-spring-ai-round2-qa-notes.md`](../../learning-spring-ai-round2-qa-notes.md)
- 학습 회고: [`docs/round2/retrospective/round2-retrospective.md`](../retrospective/round2-retrospective.md) §1-3 / §1-5 / §1-6
- 실패 관찰: [`docs/round2/failure-observations/round2-failure-observations.md`](../failure-observations/round2-failure-observations.md) §관찰 1, 3
- Spring AI 1.0 GA ChatClient docs (SimpleLoggerAdvisor): https://docs.spring.io/spring-ai/reference/api/chatclient.html
- Round 2 강의 자료 4부 (Observability): https://www.notion.so/36a2e1bd53b281449fa5c1abf444072d
