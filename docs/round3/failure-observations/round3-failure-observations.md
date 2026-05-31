# Round 3 — 실패·위험 관찰

> 형식적 "됐다/안 됐다"가 아니라 **시스템이 어떻게 망가지는지**를 출력 그대로 기록한다.
> 실행: qwen2.5, 2026-05-31. 원본 [raw/scenarios.md](../raw/scenarios.md).

## 관찰 1 — qwen2.5 응답 언어 불안정 (중국어 누수)
**현상**: Memory가 붙자 응답에 중국어가 섞이거나(시나리오 1 끝 `下一步行动建议是什么？`), **응답 전체가 중국어**로 나왔다(시나리오 2 양 턴 전부). 2단계 A의 turn 5/7/9/10도 한국어→중국어 혼용.
**원인 추정**: (a) qwen2.5(7B급)의 다국어 혼선 — Round 2에서도 관찰. (b) 누적된 멀티턴 컨텍스트 + SystemMessage가 중간에 위치(관찰 2)하면서 "한국어로만" 지시의 효력이 약해짐.
**영향**: 한국 고객에게 중국어 응답 = 시스템 오류로 오인, 신뢰 붕괴.
**대응**: System Prompt에 출력 언어 고정 규칙 강화 / 더 큰 모델 / 출력 언어 후처리 가드.

## 관찰 2 — SystemMessage가 Memory 뒤에 위치
**현상**: 2회차 프롬프트 `messages[]` 순서가 `[과거USER, 과거ASSISTANT, SYSTEM, 새USER]`. SystemMessage가 맨 앞이 아니라 **대화 중간**에 들어간다(DEBUG 로그 line 139).
**원인**: `MessageChatMemoryAdvisor`가 조회한 과거 메시지를 prepend하는 방식상, `defaultSystem`이 그 뒤에 놓인다.
**영향**: system 위치에 민감한 모델에서 지시 효력 약화 가능 → 관찰 1과 연결.
**대응**: `PromptChatMemoryAdvisor`(과거를 system 텍스트에 녹임) 비교 검토 / system 재배치.

## 관찰 3 — maxMessages=2: 지시 대명사 해결 붕괴 ⭐
**현상**(2단계 실험 B, 동일 10턴):
- turn 7 `"그거 취소해주세요"` → *"취소할 주문의 정확한 주문번호를 알려주시면 도와드리겠습니다"* (해결 실패)
- turn 9 `"그 주문 라이더 위치 다시 확인"` → *"정확한 주문번호를 다시 한번 말씀해주실 수 있을까요?"* (실패)
- turn 10 요약 → 1234만 남고 1235·이전 맥락 소실
**원인**: 윈도우가 2면 직전 USER/ASSISTANT 한 쌍만 남는다. orderId를 담은 옛 메시지가 2턴 만에 창문 밖으로 밀려나 "그거"가 가리킬 대상이 사라진다.
**영향**: 지시 대명사 해결 **2/5** (A는 4/5). 상담 에이전트로 실격.
**대응**: maxMessages ≥ 평균 대화 길이×2 ([ADR-001](../adr/ADR-001-max-messages-20.md)).

## 관찰 4 — Spring AI 1.0은 H2 스키마 스크립트를 동봉하지 않는다 ⭐
**현상**: jdbc 프로필 첫 기동 시 부트 실패:
```
No schema scripts found at location 'classpath:org/.../schema-h2.sql'
```
**원인**: 동봉 스크립트는 `postgresql / mariadb / sqlserver / hsqldb` 뿐. **`schema-h2.sql`이 없다.** `MODE=PostgreSQL`을 줘도 스키마 초기화기는 실제 제품명(H2)으로 스크립트를 찾는다.
**영향**: 강의 자료의 `jdbc:h2;MODE=PostgreSQL` 설정이 1.0.0 GA에선 그대로 안 됨.
**대응**: `spring.ai.chat.memory.repository.jdbc.platform=postgresql` 로 강제 → `schema-postgresql.sql` 로드, H2(PostgreSQL 모드)가 실행. ([application-jdbc.yml](../../src/main/resources/application-jdbc.yml))

## 관찰 5 — 존재하는 주문(1235)을 "조회 불가"로 처리 (Tool 호출 실패)
**현상**: 2단계 A/C의 turn 3 `"2024-1235 메뉴 뭐였죠?"` → *"2024-1235로 조회할 수 없습니다"*. 실제 1235는 시드된 CREATED 주문.
**원인**: Memory 문제가 아니라 **qwen2.5의 Tool 호출 신뢰도**(Round 2 관찰: 60~80%). `getOrderDetail(2024-1235)`를 호출하지 않고 환각.
**영향**: 멀쩡한 주문을 "없다"고 안내 → 고객 혼란. 이후 "그 버거세트"를 1234에 잘못 귀속(관찰 9)로 이어짐.
**대응**: 더 큰 모델 / Tool 강제 호출 정책 / 조회 실패 시 재시도.

## 관찰 6 — 세션 누락 시 `"default"` 공유 = 보안 사고
**현상**(설계): `X-Session-Id` 미전송 시 `defaultValue="default"`로 폴백 → 모든 그런 요청이 **한 칸을 공유**.
**원인**: 개발 편의 폴백. 단일 사용자 테스트에선 안 드러남(그래서 "테스트 통과·운영 발견").
**영향**: 구버전 클라이언트/어뷰저의 대화가 서로 노출 → 개인정보 사고. LLM이 타인의 orderId로 Tool 호출.
**대응**: 프로덕션 폴백 금지(헤더 없으면 400), 서버 발급 UUID / JWT 서명 검증 ([ADR-002](../adr/ADR-002-session-id-header.md)). 반대로 시나리오 4에서 **정상 분리**(s4a↔s4b 격리)는 확인됨.

## 관찰 7 — 응답 포맷 카테고리가 자연어로 누수
**현상**: `/assistant`(자연어 엔드포인트) 응답에 *"예상 처리 시간 카테고리는 **WITHIN_30MIN**입니다"* 가 그대로 출력(시나리오 5, 2단계 다수).
**원인**: System Prompt `[응답 포맷] 4)`의 분류 지시를 LLM이 자연어 답변에 노출.
**영향**: 내부 분류 라벨이 고객에게 노출 — UX 저하.
**대응**: 분류는 Structured Output(`/support`) 전용으로 분리, 자연어 프롬프트에선 카테고리 문구 제거.

## 관찰 8 — kill -9 + h2:file: 마지막 메시지 미플러시
**현상**: 3단계에서 2턴(4메시지) 대화 후 `kill -9` → 파일 reopen 시 **3행만**(마지막 ASSISTANT 누락). 단 대화 자체는 재시작 후 유지됨.
**원인**: H2 MVStore 쓰기 버퍼링 + Spring AI `saveAll`의 **비트랜잭셔널 delete+insert**(이슈 #3153). SIGKILL은 graceful flush 기회를 안 줌.
**영향**: 강제 종료/크래시 시 최근 대화 일부 유실 가능.
**대응**: graceful shutdown(SIGTERM) + 운영 RDBMS(PostgreSQL) ([ADR-003](../adr/ADR-003-inmemory-vs-jdbc.md)).

## 관찰 9 — 컨텍스트 혼동에 의한 사실 오류
**현상**: 2단계 A turn 4 `"아 그 버거 세트"` → *"2024-1234 주문의 메뉴는 버거 세트"*. 실제 1234는 허니콤보, 버거세트는 1235.
**원인**: 1235 조회 실패(관찰 5) 후, 누적 컨텍스트에서 "버거세트"를 가장 활성화된 주문(1234)에 잘못 귀속.
**영향**: Memory가 오히려 **틀린 정보를 그럴듯하게** 굳힐 수 있다 — 잘못된 ASSISTANT 응답이 다음 턴 컨텍스트로 재주입되는 오염 루프.
**대응**: ASSISTANT 응답에 사실(주문번호↔메뉴)을 못박아 후속 턴이 재사용하게 / Tool 재조회 우선.
