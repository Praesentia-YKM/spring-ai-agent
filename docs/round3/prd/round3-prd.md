# Round 3 PRD — 대화 맥락 관리와 메모리 설계

## 한 줄 메시지
**대화 메모리는 "있으면 좋은 기능"이 아니라 상담 에이전트의 전제 조건이다.** Memory 없는 에이전트는 매 호출이 독립인 단발 챗봇일 뿐이다.

## 배경 — 왜 이 라운드가 필요한가
LLM 호출은 stateless다. 매 요청이 독립 함수라서, 직전 대화를 모델이 기억하지 못한다. "그거 취소해줘"의 "그거"를 해석하려면 **이전 대화를 모델 바깥(서버)에 들고 있다가 매 호출마다 프롬프트에 다시 끼워 넣어야** 한다. Round 2의 Tool Calling이 "무엇을 실행할지"를 풀었다면, Round 3은 그 Tool이 **어떤 orderId로 호출될지**를 대화 맥락에서 끌어오는 문제를 푼다.

## 목표
- Spring AI `ChatMemory` / `ChatMemoryRepository` / `MessageChatMemoryAdvisor` 3레이어를 직접 조립한다.
- `MessageWindowChatMemory`로 슬라이딩 윈도우를 구성하고, 크기 제한이 필요한 이유를 토큰 관점에서 증명한다.
- `X-Session-Id` 헤더 ↔ `ChatMemory.CONVERSATION_ID`로 고객별 세션을 분리한다.
- InMemory ↔ JDBC 저장소 선택 기준을 운영 조건(재시작/멀티 인스턴스/감사)으로 판단한다.

## 범위 (In)
| 영역 | 산출물 |
|------|--------|
| 3레이어 조립 | `ChatMemoryConfig` (3 Bean, `@Profile("!jdbc")`) |
| 세션 분리 | `X-Session-Id` 헤더 → `CONVERSATION_ID` param, `SessionController` |
| 크기 정책 실험 | maxMessages 20 / 2 / MAX_VALUE 정량 비교 |
| 저장소 전환 | JDBC 프로필 + H2, 재시작 영속성 실험 |
| Observability | 턴별 입력 토큰 증가 관찰, 프롬프트 주입 증거 |
| AI 코드 리뷰 | AI 생성 메모리 코드의 프로덕션 결함 + 개선 |

## 범위 밖 (Out)
- 대화 요약(summarization) 메모리 — Spring AI 1.0 미제공, 본 라운드는 윈도우만.
- 민감정보 마스킹/암호화 — Round 5 Guardrail로 이연.
- Redis 등 외부 캐시 저장소 실구성 — 의사결정 트리에서 비교만.

## 평가 기준 (페어 리뷰어 3축)
1. **설계 결정의 근거** — maxMessages·저장소·세션 식별을 왜 그렇게 골랐는가.
2. **실패 관찰의 구체성** — 경계가 무너질 때(특히 maxMessages=2, 세션 누락) 시스템이 어떻게 망가지는지 출력 그대로.
3. **다음 라운드 연결** — Memory와 RAG(Round 4)를 Advisor 체인으로 어떻게 묶을 것인가.

## 검증 환경
- Spring Boot 3.4.1 / Spring AI 1.0.0 / JDK 17(toolchain)
- Ollama `qwen2.5:latest`, temperature 0.3
- 엔드포인트: `POST /api/v1/assistant`(+ `X-Session-Id`), `GET/DELETE /api/v1/session/**`
