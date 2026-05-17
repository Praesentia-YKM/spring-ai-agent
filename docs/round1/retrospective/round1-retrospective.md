# Round 1 — 학습 회고

> 5개 sub-topic × 11개 🟢 아하 모먼트의 학습 흐름 + 잘된 점·부족했던 점·다음 라운드 적용 사항.

## 학습 흐름 한 줄 정리

> **Spring AI 의 `ChatClient` 한 줄 뒤에서 BeanOutputConverter / Advisor / Streaming 이 어떻게 협업하는지를 자기 손에 잡히게 알게 됐고, 그 위에서 "LLM 은 판단, 실행은 우리 서버" 원칙이 실제 코드 결정(필드 폐기·enum 추상화·엔드포인트 분리) 에 어떻게 드러나는지를 직접 경험했다.**

## 5개 sub-topic × 11개 아하 모먼트

| # | sub-topic | 아하 모먼트 | 도출 방식 |
|---|----------|----------|----------|
| 1 | 판단자/실행자 경계 | 한 줄 메시지가 코드 결정의 기준이 된다 | 큰 그림 전반에서 흡수 |
| 2 | ChatClient + Structured Output | "인터럽트했겠지" — OutputConverter 가로채기 직감 | Jackson 한계 사고실험 |
| 2 | ChatClient + Structured Output | DTO 스펙 = JSON Schema 자동 주입 | 양자택일 + docs 검증 |
| 3 | Prompt Lab + Temperature | "항상 같은 답 안 나옴" — categoryConsistency 의미 | 4/5 계산 |
| 3 | Prompt Lab + Temperature | Temperature = 분포 sharpness 다이얼 (지능과 무관) | 토큰 분포 히스토그램 시각화 |
| 3 | Prompt Lab + Temperature | **Temperature 는 거짓 검출 도구가 아니다** (학습자 비판으로 정정) | 메타 워딩 비판 |
| 4 | Streaming | 첫 글자 도착 시점 단축이 본질 | 시간축 비교 시각화 |
| 4 | Streaming | 부분 JSON 파싱 실패 — Streaming + Structured Output 충돌 | 학습자가 자기 키워드로 도출 |
| 5 | Advisor | `order = 100` = 체인 바깥쪽 = 총 왕복 시간 측정 | 체인 순서 시각화 |
| 실험 | 1단계 결과 분석 | 결과에서 다음 학습이 자라난다 — 실행 후 2단계 동기 도출 | 시나리오 3종 결과 분석 |
| 측정 | 4단계 advisor | Structured Output 은 무료가 아니다 — JSON Schema 토큰 비용 | 입력 692:출력 89 측정 |
| 실험 C | [금지]/[규칙] | [금지] 는 [규칙] 의 단순 중복이 아니라 다중 필드 일관성·적극적 입장 표명 강제 | 실험 C 3종 비교 |

## 잘된 점

1. **비판적 사고로 멘토 설명 정정** — Temperature 가 "거짓을 검출 가능하게 한다"는 멘토의 부정확한 워딩에 의문 제기로 정정을 끌어냈다. 이 능력이 production AI 시스템의 무모한 신뢰를 막아주는 가장 강력한 무기.
2. **메커니즘 수준까지 내려가서 학습** — `temperature` 를 "다이얼" 비유로만 풀면 막혔지만, 토큰 분포 히스토그램 시각화 + `softmax(logits/T)` 수학까지 내려가서 진짜 이해.
3. **실험 데이터로 결정 검증** — temperature 0.3 / 구조화 vs 단순 / [금지] 제거 / System Prompt 2배 등 모든 결정을 실제 실행으로 검증.
4. **코드와 학습 흐름의 1:1 매핑** — 학습 회고의 큰 그림 다이어그램의 `[?]` 박스 3개가 1단계·3단계·4단계 코드로 정확히 까졌다.

## 부족했던 점

1. **`categoryConsistency` 만으로는 프롬프트 품질 측정 한계 발견** — 실험 A/B 가 정량 지표 차이를 못 잡았다. 자유 텍스트 다양성·기대값 대비 정확도 같은 보조 지표가 함께 필요.
2. **응답 시간 측정의 통계적 신뢰성 부족** — 실험 D 의 응답 시간 차이는 단일 호출만으로 결론 어려움. 다회 평균(10회 이상) 측정이 필요.
3. **다중 필드 자기 모순 검출 메커니즘 부재** — 실험 C1 에서 `summary` 는 거절하면서 `nextAction` 은 약속하는 자기 모순을 발견했지만, 자동 검출 시스템은 Round 1 범위 밖. Round 5 Guardrail 로 이월.
4. **AI 코드 리뷰는 학습자 본인 작업으로 남음** — Quest 4단계 평가 핵심 중 하나이지만 멘토가 추측으로 채울 수 없는 영역.

## 다음 라운드(Round 2 Tool Calling)에 적용할 것

1. **`refundEligibility` 부활 시도** — `@Tool getRefundPolicy(orderId)` 로 LLM 이 결제 시스템을 직접 호출. ADR-004 의 복원 경로.
2. **`EstimatedResolution` enum → 실제 분 수치 매핑 Tool** — LLM 카테고리 판단 + 시스템 SLA 데이터 결합. ADR-003 의 자연스러운 확장.
3. **Category 7값으로 확장 + Tool 라우팅** — `COMPLAINT` 카테고리 추가 시 `routeToHumanAgent(complaintType)` 같은 Tool 호출 트리거.
4. **categoryConsistency 외 보조 측정 지표** — 자유 텍스트 다양성, 기대값 대비 정확도 등을 PromptLab 에 추가하는 실험.

## 학습 회고의 큰 그림 — 백지 그리기 자가 점검

학습자가 코드·노트 보지 않고 백지에 그려봐야 할 것:

1. `ChatClient.prompt().user(...).call().entity(Class)` 한 줄이 내부적으로 호출하는 박스 3개와 각각의 역할.
2. `.stream()` 과 `.call()` 의 타입 시스템 차이 — 왜 `.stream().entity()` 가 존재하지 않는가.
3. Advisor 체인에서 `order` 가 높을수록 체인 바깥쪽인 이유 (요청 들어올 때 vs 응답 나갈 때의 순서).

학습 회고의 다이어그램과 비교 → 빠진 곳·틀린 곳이 학습자의 약점이고, 그 약점이 Round 2 의 첫 점검 포인트가 된다.

## 학습 스타일 메모 (자기 분석)

- 양자택일·짧은 답변·시각화 우선이 잘 통함.
- 메커니즘 내부 동작 단계에서 추상에 머물면 막힘 → 한 단계 더 내려가야 풀림.
- 멘토 설명에 의문 제기로 정정을 끌어내는 비판적 사고가 강점.
- 코드·문서 작업은 멘토에 위임하되, 의사결정·검증·산출물 정리에 집중하는 협업 스타일.
