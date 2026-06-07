# round4-04 · [정책 인용 규칙] — 환각 방지(Fallback)

> **사전 완성(레퍼런스)**: ① `RagConfig` ✅ ② `KnowledgeLoader` ✅ ③ Advisor 체인(두 컨트롤러) ✅
> **이 worktree의 초점 기능 ④**: `BaedalPrompt`의 TODO J — SYSTEM_PROMPT에 `[정책 인용 규칙]`을
> 직접 작성해 환각을 막는다. ④까지 끝내면 RAG 파이프라인 전체가 동작한다.

## 0. 사전 요건

```bash
docker compose up -d
ollama pull qwen2.5 && ollama pull qwen3-embedding:0.6b
```

## 1. 학습 포인트 (개념)

- **환각 2중 방어** — 검색 결과가 빈약/무관해도 LLM은 꾸며낸다. 둘 다 있어야 막힌다:
  1. `similarityThreshold`(①에서 0.5) — 무관 문서를 Top-K에서 탈락
  2. `[정책 인용 규칙]`(④, 여기) — "Context에 없으면 지어내지 말고 Fallback 문구로 답하라"
- **임계값만으로는 부족** — 임계값은 "무관 문서 제거"만 한다. "LLM이 없는 얘기를 지어내는 것"은 프롬프트 규칙으로 막아야 한다.
- **원문 수치 유지** — "24시간 이내"를 "하루 안에"로 반올림하면 감사 추적이 깨진다.

## 2. 초점 TODO (BaedalPrompt)

`[정책 인용 규칙]` 섹션을 5가지 축으로 작성한다.

### 구현 힌트 (모범 답안 골격)

```
[정책 인용 규칙]
- 환불, 취소, 배달 지연 보상, 쿠폰 관련 질문은 반드시 제공된 Context를 근거로만 답합니다.
- Context에서 답을 찾을 수 없으면 추측하지 말고 이렇게 답합니다:
  "해당 내용은 확인이 필요합니다. 상담원 연결로 도와드리겠습니다."
- 정책을 인용할 때는 원문의 수치/조건(예: "60분 이상", "24시간 이내", "1,000원 쿠폰")을
  그대로 사용합니다. 임의로 반올림하거나 단순화하지 않습니다.
- 여러 정책이 관련될 때는 고객 상황(주문 상태, 경과 시간)에 가장 맞는 정책을 선택합니다.
- 단순 인사·잡담·상담 범위 밖 질문에는 Context를 인용하지 않고
  "고객님, 저는 주문/배달/환불 관련 상담을 도와드리고 있어요"로 범위를 안내합니다.
```

> 5가지 축: (1) Context 근거만 (2) Fallback 문구 (3) 원문 수치 유지 (4) 복수 정책 우선순위 (5) 범위 밖 처리.

## 3. 설계 결정 질문 (README)

- "similarityThreshold로 거르면 되지 않나?" — 왜 Fallback 문구를 프롬프트에도 박아야 하는가?
- Fallback 문구를 프롬프트에 고정하면 LLM 판단과 독립적으로 일관된다. 이 문구를 바꾸면 고객 경험이 어떻게 달라지나?

## 4. 검증 — 1단계 시나리오 5종 (직접 확인)

```bash
./gradlew bootRun   # 신규 7건 적재 확인

# 1) 환불(배달 완료 후)  → refund-after-delivered/refund-basic, "24시간 이내" 등 원문
curl -s -X POST http://localhost:8080/api/v1/assistant -H "Content-Type: application/json" \
  -H "X-Session-Id: s1" -d '{"message":"배달 완료 후에도 환불 받을 수 있나요?"}'
# 2) 취소  → cancel-policy, "조리 시작 전/후" 구분
curl -s -X POST http://localhost:8080/api/v1/assistant -H "Content-Type: application/json" \
  -H "X-Session-Id: s2" -d '{"message":"결제 후 바로 취소하면 환불되나요?"}'
# 3) 쿠폰  → coupon-faq, "중복 적용 불가/최소 주문 금액"
curl -s -X POST http://localhost:8080/api/v1/assistant -H "Content-Type: application/json" \
  -H "X-Session-Id: s3" -d '{"message":"쿠폰 중복 사용되나요?"}'
# 4) 개인정보  → [금지] 규칙으로 거절, 전화번호 노출 없음
curl -s -X POST http://localhost:8080/api/v1/assistant -H "Content-Type: application/json" \
  -H "X-Session-Id: s4" -d '{"message":"사장님 전화번호 알려주세요"}'
# 5) 도메인 밖 → Fallback ("상담 범위가 아닙니다"), 환각(비빔밥 추천 등) 없어야
curl -s -X POST http://localhost:8080/api/v1/assistant -H "Content-Type: application/json" \
  -H "X-Session-Id: s5" -d '{"message":"오늘 점심 뭐 먹을까요?"}'
```

> 실패 관찰(2단계 예고): `[정책 인용 규칙]`을 통째로 주석 처리하고 시나리오 5를 다시 보내면
> LLM이 "비빔밥을 추천드려요"처럼 환각한다. 규칙 복원 전/후를 비교 캡처하라.

## 5. 자가 점검 체크리스트

- [ ] `[정책 인용 규칙]`에 5가지 축이 모두 포함
- [ ] 시나리오 1~3: 정책 **원문 수치**가 응답에 그대로 등장
- [ ] 시나리오 4: 전화번호 미노출(거절)
- [ ] 시나리오 5: Fallback 문구로 응답(환각 없음)
- [ ] `[정책 인용 규칙]` 제거 시 환각이 실제로 발생함을 확인(실패 관찰)

## 6. 다음 단계 (실험)

①②③④ 완성 후 → 실험 worktree(별도 생성): ⑤ 청킹 100/800/2000 비교, ⑥ Advisor order 20↔5 뒤집기, ⑦ 토큰 비용(a/b/c). 필요할 때 알려주면 `round4-exp-*` worktree를 만들어 둔다.
