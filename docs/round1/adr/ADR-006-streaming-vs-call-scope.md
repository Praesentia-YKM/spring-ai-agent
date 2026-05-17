# ADR-006: Streaming `.stream()` 은 자유 텍스트 엔드포인트에만 적용

- **Status:** Accepted
- **Date:** 2026-05-17
- **Round:** 1

## Context

`.stream()` 이 사용자 체감 속도를 폭발적으로 개선한다는 사실(첫 글자 도착 0.3s 대 동기 7s)을 학습한 후, **모든 엔드포인트에 적용할지** 결정해야 한다.

## Decision

**자유 텍스트 엔드포인트에만 Streaming 을 적용한다.**

| 엔드포인트 | 응답 형태 | 호출 방식 | 이유 |
|-----------|---------|---------|------|
| `/api/v1/chat` | 자유 텍스트 (`String`) | `.call().content()` | 단순 채팅 |
| `/api/v1/chat/stream` | 자유 텍스트 흐름 (`Flux<String>`) | `.stream().content()` | UX 우선 |
| `/api/v1/support` | DTO (`SupportResponse`) | `.call().entity(Class)` | Structured Output 동기 호출 |
| `/api/v1/prompt-lab` | 통계 DTO (`PromptLabResult`) | `.call().entity(Class)` | Structured Output 동기 호출 |

## Consequences

### 본질적 충돌의 근거
- `.entity(Class)` 는 LLM 응답 JSON 전체를 받아 Jackson 으로 역직렬화한다.
- `.stream()` 은 토큰 단위로 부분 JSON 을 흘려보낸다.
- 부분 JSON (`{"summa`, `{"summary":"주`) 을 Jackson 에 던지면 `JsonParseException` 폭발.
- **Spring AI 가 `StreamResponseSpec` 에 `.entity()` 메서드를 의도적으로 두지 않아** 타입 차원에서 잘못된 조합을 거부.

### 긍정
- **명확한 룰**: "사람이 직접 읽는 자유 텍스트" 는 stream, "시스템이 파싱하는 DTO" 는 call.
- **타입 시스템이 디자인 결정을 자동 강제**. 잘못된 조합은 컴파일 단계에서 막힘.

### 부정 / Trade-off
- **`/api/v1/support` 사용자는 7초 대기**. UX 손해.
- **하이브리드 패턴 필요 시 엔드포인트 분리** — 자유 텍스트(`summary`) 는 stream, 메타데이터(`category`/`urgency`) 는 call 로 받으려면 두 호출이 필요 (LLM 호출 2배) 또는 단일 호출 후 서버 측에서 분리.

### 프론트엔드 측 변화
- `fetch().then(json)` → `EventSource` 또는 `fetch().getReader()` 로 chunk 수신.
- `data:` 프레임 누적 버퍼링.
- "응답 생성 중" UI + 종료 시점 처리 (`[DONE]` 또는 close 이벤트).
- 부분 응답에서 네트워크 끊김 복구 로직.

## Alternatives Considered

| 대안 | 기각 사유 |
|------|----------|
| 모든 엔드포인트 Streaming | `.entity()` 와 본질 충돌. 컴파일도 안 됨. |
| 모든 엔드포인트 동기 | 자유 텍스트 채팅의 UX 손실 |
| Streaming 으로 raw 텍스트 받고 클라이언트가 마지막에 JSON 파싱 | DTO 의 타입 안전성 손실, enum 검증 손실 |

## References

- 학습 회고 Q4·Q5 (Streaming 본질 + Structured Output 충돌)
- 강의 본문 4부
