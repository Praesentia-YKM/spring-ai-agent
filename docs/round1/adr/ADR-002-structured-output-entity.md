# ADR-002: Structured Output 채택 (`.entity(SupportResponse.class)`)

- **Status:** Accepted
- **Date:** 2026-05-17
- **Round:** 1

## Context

`/api/v1/support` 엔드포인트는 시스템이 카테고리·긴급도·다음 액션 등으로 분기 처리할 수 있어야 한다. LLM 응답을 어떤 형태로 받을지 결정해야 했다.

- **선택지 A**: `.call().content()` 로 raw 문자열을 받고 정규식·문자열 파싱으로 분류.
- **선택지 B**: `.call().entity(SupportResponse.class)` 로 DTO 직접 받기.

## Decision

**선택지 B 채택** — `.entity(SupportResponse.class)`.

내부 메커니즘:
1. Spring AI `BeanOutputConverter` 가 `SupportResponse` 클래스 구조에서 **JSON Schema (DRAFT_2020_12)** 자동 생성.
2. 생성된 Schema 가 프롬프트의 `{format}` 위치에 자동 주입 → LLM 이 처음부터 JSON 만 뱉도록 강제.
3. 응답이 도착하면 Jackson 으로 역직렬화 → DTO 인스턴스 반환.

## Consequences

### 긍정
- **타입 안전 + 컴파일 타임 검증** — `category()` 가 enum 이므로 잘못된 값은 역직렬화에서 거부.
- **시스템 분기 로직 단순** — 정규식·문자열 split 없이 객체 필드 직접 사용.
- **문서화 효과** — `SupportResponse` 클래스 자체가 응답 스키마 명세.
- **enum 가능값 제약** — `Category` enum 5개 외 값은 자동 거부 → 카테고리 분류 안정성 ↑.

### 부정 / Trade-off
- **숨겨진 입력 토큰 비용** — JSON Schema 자동 주입으로 입력 토큰이 출력의 7.7배 (실제 측정: 692 vs 89). 같은 시나리오라도 `.content()` 호출보다 토큰 비용 ↑.
- **Streaming 과 본질 충돌** — `.stream() + .entity()` 조합 불가. Spring AI 가 `StreamResponseSpec` 에 `.entity()` 메서드를 의도적으로 두지 않음 (ADR-006 연관).
- **클래스 구조 변경에 민감** — 필드 추가·변경 시 LLM 응답 형식이 바뀌고 일관성 재측정 필요.

## Alternatives Considered

| 대안 | 기각 사유 |
|------|----------|
| `.content()` + 정규식 파싱 | 강의 본문 명시 "**텍스트 파싱 금지**". 부서지기 쉽고 enum 값 보장 못 함. |
| `.content()` + 별도 LLM 호출로 분류 | 2회 LLM 호출 → 비용·지연 2배 |
| Function Calling 으로 분류 받기 | Round 2 학습 주제. 이번 라운드는 Structured Output 우선. |

## References

- 학습 회고 Q1·Q2 (`.entity` 메커니즘 도출 흐름)
- Spring AI 공식 docs — Structured Output Converter: <https://docs.spring.io/spring-ai/reference/api/structured-output-converter.html>
