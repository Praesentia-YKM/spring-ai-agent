# Round 1 — PRD (Product Requirements Document)

> 루퍼스 부트캠프 "Spring AI 배달 상담 에이전트" 6주 과정의 **Round 1 미션 명세**.

## 1. 목표

배달 고객 상담 도메인의 **첫 LLM 엔드포인트** 를 구현하고, 그 위에서 **Spring AI 의 5개 핵심 개념**을 직접 손에 잡히게 학습한다.

**라운드 한 줄 메시지:** _"LLM 은 판단자, 실행은 우리 서버가 한다."_
판단과 실행의 경계를 어디에 긋는지가 코어이며, 이 한 줄이 모든 설계 결정의 기준이 된다.

## 2. 범위 (In Scope)

- `ChatClient` 기반 `/api/v1/support` 엔드포인트 (Structured Output 반환)
- 도메인 System Prompt 설계 ([역할]/[규칙]/[금지]/[응답 포맷] 4 섹션)
- `BeanOutputConverter` 가 자동 처리하는 JSON Schema 주입 메커니즘 학습
- Prompt Lab 정량 비교 + 실패 관찰 ([금지] 제거 공격 시나리오)
- SSE Streaming 엔드포인트 (`/api/v1/chat/stream`)
- `CallAdvisor` 기반 Observability — 응답 시간·토큰 사용량 로깅

## 3. 범위 외 (Out of Scope)

- Tool Calling (`@Tool`) — Round 2
- RAG / VectorStore — Round 4
- Guardrail / 사람 상담사 escalation — Round 5
- 인증·인가·세션 메모리·다중 사용자 처리

## 4. 기능 요구사항

| ID | 요구사항 | Quest 단계 |
|----|---------|----------|
| F-1 | `POST /api/v1/support` 는 자유 텍스트 입력을 받아 `SupportResponse` JSON 을 반환한다 | 1단계 |
| F-2 | `BaedalPrompt.SYSTEM_PROMPT` 가 모든 `/api/v1/support` 호출에 자동 적용된다 | 1단계 |
| F-3 | `SupportResponse` 에 도메인 의미를 갖는 필드 1개 이상을 추가한다 (선택 근거 필수) | 1단계 |
| F-4 | `POST /api/v1/prompt-lab` 는 임의 `systemPrompt + message + repeat` 입력으로 N회 호출하고 카테고리 일관성 통계를 반환한다 | 2단계 |
| F-5 | `POST /api/v1/chat/stream` 은 SSE 로 토큰 단위 응답을 흘려보낸다 (`text/event-stream`) | 3단계 |
| F-6 | `PerformanceLoggingAdvisor` 가 모든 LLM 호출에 대해 응답 시간(ms)·입력/출력/총 토큰을 로깅한다 | 4단계 |
| F-7 | `PerformanceLoggingAdvisor` 는 `null` 메타데이터 시에도 안전하게 통과한다 (provider 차이 / Mock Advisor 대비) | 4단계 |

## 5. 비기능 요구사항

| 영역 | 요구사항 |
|------|---------|
| 응답 시간 | 동기 `.call()` 평균 7s 내외 (qwen2.5 로컬), Streaming 첫 글자 0.3~0.5s |
| 토큰 비용 | `PerformanceLoggingAdvisor` 로 측정 가능. System Prompt 길이는 비용 결정 요인. |
| 일관성 | 동일 시나리오 5회 호출 시 `categoryConsistency >= 0.8` 권장 (단, 시나리오·도메인 특성 고려) |
| 관찰 가능성 | `application.yml` 의 `logging.level: org.springframework.ai: DEBUG` 로 프롬프트 전문 확인 가능 |
| 결정성 | `temperature=0.3` — 일관성과 자연스러움의 타협점 (ADR-005 참조) |

## 6. 인수 시나리오

### 1단계
- ✅ 시나리오 1: `"주문번호 2024-1234 배달 어디쯤에 있어요?"` → `category=DELIVERY`, `estimatedResolution=WITHIN_30MIN`
- ✅ 시나리오 2: `"방금 시킨 주문 취소하고 싶어요. 환불은 얼마나 걸려요?"` → JSON 정상 반환 (단, `category` 가 `ORDER` 로 나오는 분류 흔들림 관찰됨)
- ✅ 시나리오 3: `"라이더가 음식을 엎었다는데 보상 받을 수 있나요?"` → JSON 정상 반환 (`urgency=NORMAL` 의 분류 약점 관찰됨)

### 2단계
- ✅ 실험 A (구조화 프롬프트 5회) vs 실험 B (단순 프롬프트 5회) 비교 결과 기록
- ✅ 실험 C: [금지] 제거 + 공격 시나리오 3종의 LLM 원본 응답 기록
- ✅ "프로덕션 배포 시 예상 사고" 3가지 작성

### 3단계
- ✅ `curl -N` 으로 `data:` SSE 프레임 순차 수신 확인

### 4단계
- ✅ `PerformanceLoggingAdvisor` 가 `LLM 호출 완료 — {ms} | 입력 토큰: {n} | 출력 토큰: {n} | 총 토큰: {n}` 로깅
- ✅ System Prompt 1x vs 2x 길이 비교 → 입력 토큰 변화 측정 (실험 D 결과: +52.7%)

## 7. 의존성 / 스택

- Spring Boot 3.4.1, Java 17
- Spring AI 1.0.0 GA
- `spring-ai-starter-model-ollama`
- `spring-boot-starter-web` + `spring-boot-starter-webflux` (Streaming 용)
- Ollama qwen2.5 (로컬 4.7GB)
- Lombok

## 8. 평가 기준 (Quest 명시)

> **코드보다 설계 결정 문서 / 실패 관찰 기록의 품질이 더 큰 비중을 차지한다.**

- **Round 1 핵심 평가축:**
  - (1) Prompt Lab 정량 비교 수치 (categoryConsistency)
  - (2) [금지] 제거 후 LLM 원본 출력 인용
- 부분 제출 허용 (1단계만 해도 인정).

## 9. 산출물

| 산출물 | 위치 |
|--------|------|
| 구현 코드 | `src/main/java/com/baedal/support/*.java` |
| README 결과 보고서 | `README.md` |
| PRD (본 문서) | `docs/round1/prd/round1-prd.md` |
| ADRs (7개 결정 기록) | `docs/round1/adr/ADR-00X-*.md` |
| 학습 회고 (Q&A 흐름 + 11개 아하 모먼트 통합) | `docs/round1/retrospective/round1-retrospective.md` |
| 실패 관찰 (Prompt Lab 실험 데이터 + 공격 시나리오 통합) | `docs/round1/failure-observations/round1-failure-observations.md` |

## 10. 다음 라운드와의 연결

- Round 2 (Tool Calling) → 1단계에서 폐기된 `refundEligibility` 가 부활 (`@Tool getRefundPolicy(orderId)`)
- Round 4 (RAG) → System Prompt 길이를 늘리는 대신 외부 컨텍스트로 의미 주입
- Round 5 (Guardrail) → 실패 관찰에서 발견한 다중 필드 자기 모순·자사 부정 묵인·협박 미감지 대응
