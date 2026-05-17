# ADR-001: Spring AI 1.0 + Ollama (qwen2.5) 스택 채택

- **Status:** Accepted
- **Date:** 2026-05-17
- **Round:** 1

## Context

배달 상담 AI 에이전트의 첫 엔드포인트를 구현해야 한다. LLM 호출 라이브러리와 모델 provider 를 결정해야 했다.

## Decision

- **Spring AI 1.0.0 GA** + **`spring-ai-starter-model-ollama`** + **Ollama qwen2.5 로컬 모델**.

## Consequences

### 긍정
- **API Key 없이 즉시 시작 가능** — 학습 환경 진입 장벽 ↓.
- **Spring AI 가 `ChatModel ↔ ChatClient` 추상화 제공** — provider 교체가 한 줄 의존성 변경으로 가능.
- **Spring 생태계와 자연스럽게 연결** — Bean, DI, AOP, Advisor 가 그대로 동작.
- **로컬 실행** — 데이터가 외부로 나가지 않아 학습용 시나리오 자유롭게 사용 가능.

### 부정 / Trade-off
- **응답 시간 7s 내외** — 클라우드 모델(OpenAI/Anthropic) 보다 느림.
- **GPU/RAM 부담** — qwen2.5 약 4.7GB 모델 로딩, 추론 시 8GB+ RAM 권장.
- **토큰 메타데이터가 일부 응답에서 누락 가능** — Advisor 의 `null` 방어 필수 (ADR-007 연관).
- **Spring AI 1.0 GA 가 비교적 신규** — 사전 학습된 LLM 의 API 지식이 outdated 일 가능성 → 공식 docs 우선 (룰 19).

## Alternatives Considered

| 대안 | 기각 사유 |
|------|----------|
| **OpenAI / Anthropic API** | API Key 필요, 학습 비용 발생, 학습자 데이터 외부 전송 |
| **LangChain4j** | Spring 생태계 통합도 ↓, 학습 자료 차이 |
| **순수 HTTP 클라이언트 + Ollama REST** | 추상화 부재, JSON Schema 자동 주입·Advisor 등 핵심 학습 포인트 직접 구현해야 함 |

## References

- 강의 본문 "1.1 왜 Spring AI 인가" + "프로젝트 세팅"
- Spring AI 공식 docs: <https://docs.spring.io/spring-ai/reference/>
