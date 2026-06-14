# 배달 상담 AI 에이전트 — Round 4 (RAG)

Round 3의 Memory 에이전트 위에 **RAG(PgVector + 임베딩 + `QuestionAnswerAdvisor`)** 를 얹어,
"비 오는 날 보상 되나요?" 같은 **정책 문서 기반 질문**을 답하게 만든 라운드.

**라운드 한 줄 메시지:** _"RAG를 켜는 건 Advisor 한 줄이다. 어려운 건 **얼마나 쪼갤지(청크) · 몇 건 가져올지(Top-K) · 얼마부터 믿을지(임계값) · 모르면 어떻게 답할지(Fallback)** 의 경계 설계다."_

> 이 문서는 **실제로 돌려서 얻은 로그/수치**만 적었다. 원본 캡처는 [`raw/`](./raw) 폴더에 있다.
> 측정 환경: macOS · JDK 17(Zulu) · Ollama `qwen2.5`(chat) + `qwen3-embedding:0.6b`(1024d) · PgVector(pg16, Docker) · 2026-06-14.

---

## RAG 2 파이프라인 한눈에

```
[인덱싱]  knowledge/*.md ──parse──▶ FaqDocument ──TokenTextSplitter──▶ 청크들
              │                                                          │
              └────────────── EmbeddingModel(qwen3-embedding:0.6b, 1024d)┘
                                          │
                                          ▼  metadata{faqId,title,category} 동반
                                   PgVector(vector_store)   ← 앱 기동 시 1회 (중복 스킵)

[검색]    질문 ──같은 임베딩 모델──▶ 질문벡터 ──similaritySearch(topK=4, thr=0.5)──▶ Top-K 청크
              │                                                                        │
   MessageChatMemoryAdvisor(10) ─▶ QuestionAnswerAdvisor(20) "Context:" 주입 ─▶ PerformanceLoggingAdvisor(100) ─▶ LLM
```

핵심 구현 파일:
- [`RagConfig.java`](../../src/main/java/com/baedal/support/rag/RagConfig.java) — Top-K/임계값/Splitter/QA Advisor
- [`KnowledgeLoader.java`](../../src/main/java/com/baedal/support/rag/KnowledgeLoader.java) — 인덱싱 파이프라인 + 중복 방지
- [`BaedalPrompt.java`](../../src/main/java/com/baedal/support/BaedalPrompt.java) — `[정책 인용 규칙]`(Fallback) + `[도구 사용 규칙]`
- [`AssistantController.java`](../../src/main/java/com/baedal/support/AssistantController.java) / [`SupportController.java`](../../src/main/java/com/baedal/support/SupportController.java) — Advisor 체인 등록

## 빠른 시작

```bash
ollama pull qwen2.5
ollama pull qwen3-embedding:0.6b          # 1024차원, ~640MB

docker compose up -d                      # PgVector (baedal-pgvector, healthy 확인)
./gradlew bootRun                         # 기동 로그: RAG 시드 완료 — 신규 7건 / 스킵 0건

curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" -H "X-Session-Id: demo" \
  -d '{"message":"비 오는 날 배달이 늦으면 보상 받을 수 있나요?"}'
```

> **2단계 청크 실험을 위해 청크 크기를 프로퍼티로 노출**했다(재컴파일 없이 변경).
> `./gradlew bootRun --args='--baedal.rag.chunk-size=100 --baedal.rag.min-chunk-size-chars=40'`
> (`ChatMemoryConfig`의 `baedal.memory.max-messages`와 동일 컨벤션)

---

# 1단계 — RAG 기본 구현 + 시나리오 5종 (30점)

## 구현 (TODO 9개 + α)

| 파일 | 채운 TODO | 핵심 |
|---|---|---|
| `RagConfig` | A·B·C·D | `TOP_K=4`, `THRESHOLD=0.5`, `TokenTextSplitter(800,350,5,10000,true)`, `QuestionAnswerAdvisor.order(20)` |
| `KnowledgeLoader` | E·F | `Document`+metadata → `splitter.apply()` → `vectorStore.add()` / `alreadyLoaded()`는 `filterExpression("faqId == ...")` |
| `AssistantController` | G | `defaultAdvisors(memory, rag, performance)` |
| `SupportController` | H | 동일 체인 |
| `BaedalPrompt` | J | `[정책 인용 규칙]` 4축 작성 |
| `BaedalPrompt` | **+α** | `[도구 사용 규칙]` 추가 (아래 "실패 관찰 0" 참조) |

### 기동/재기동 로그 — 중복 방지 증명

```
# 첫 기동
[KnowledgeLoader] 적재 완료 — id=privacy / 청크=1개 / 카테고리=account
... (7건) ...
[KnowledgeLoader] RAG 시드 완료 — 신규 7건 / 스킵 0건 / 총 7건

# 재기동 (같은 PgVector 볼륨)
[KnowledgeLoader] 이미 적재됨 — id=refund-basic (환불 기본 정책)
[KnowledgeLoader] RAG 시드 완료 — 신규 0건 / 스킵 7건 / 총 7건     ← alreadyLoaded() 동작
```

### `vector_store` 테이블 (실측, [raw](./raw/stage1-vector-store.txt))

```
 total_rows | distinct_faq
------------+--------------
          7 |            7         ← 정책 문서가 모두 800토큰 미만이라 1문서=1청크

 n |    category          faqid          |    category    |            title
---+----------------     -----------------------+----------------+-----------------------------
 1 | account             privacy                | account        | 개인정보 및 계정 정책
 1 | cancel              cancel-policy          | cancel         | 주문 취소 정책
 1 | coupon              coupon-faq             | coupon         | 쿠폰 사용 FAQ
 2 | delivery-delay      delay-compensation     | delivery-delay | 배달 지연 보상 기준
 2 | refund              weather-delay          | delivery-delay | 기상 악화 시 배달 지연 안내
                         refund-after-delivered | refund         | 배달 완료 후 환불 정책
                         refund-basic           | refund         | 환불 기본 정책
```

## 실패 관찰 0 — RAG는 맞았는데 Tool이 답을 덮어썼다 (Tool×RAG 충돌)

> 처음 시나리오 1을 돌렸을 때 답이 엉뚱하게 **주문 2024-1234 배달 상태**로 나왔다. 로그를 까보니:

```
# 1차 LLM 응답(정상): RAG Context를 인용
ASSISTANT content="비 오는 날 자체만으로 ... 기상 특보 ... 60분 이상이어야 보상..."
            toolCalls=[getOrderDetail(orderId=2024-1234)]   ← 동시에 엉뚱한 Tool 호출
# Tool 결과가 다시 들어가 2차 LLM 응답이 "배달 상태" 답으로 덮어씀
```

**원인:** 질문에 주문번호가 없는데도 LLM이 **Tool 설명의 예시 번호 `2024-1234`** (`@ToolParam(... 예: 2024-1234)`)를 실제 주문번호로 착각해 `getOrderDetail`을 호출 → Tool 결과가 RAG 답을 덮어썼다.
**조치:** `BaedalPrompt`에 `[도구 사용 규칙]` 추가 — *"고객이 실제로 제시한 주문번호가 있을 때만 주문 도구 호출, 예시 번호를 실제 번호로 쓰지 말 것."* 이후 정책 질문은 Tool 없이 RAG로만 답한다.
**의미:** Round 3(Tool)과 Round 4(RAG)는 **같은 체인에서 충돌**할 수 있다. RAG 검색은 성공해도 Tool 루프가 답을 가로챌 수 있다는 것을 직접 관찰. (Round 5 Guardrail 예고)

## 시나리오 5종 결과 (실측)

> 각 응답은 fresh 세션. `Context:` 블록은 DEBUG 로그(Ollama TRACE 요청 본문)에서 발췌. 원본: [raw/stage1-*](./raw)

### #1 `"비 오는 날 배달이 늦으면 보상 받을 수 있나요?"` → ✅ `delivery-delay`
- **Top-K:** `delay-compensation` + `weather-delay` (2건)
- **응답(발췌):** *"기상 특보 여부와 실제 지연 시간에 따라 결정됩니다. 60분 이상 지연 시 전액 환불 검토. 비 오는 날이라는 이유만으로는 보상이 어렵습니다."*
- **Context 블록(발췌):** 지연 보상 표(`+11~29분 1,000원 쿠폰` / `+30~59분 배달비 전액 환불 또는 3,000원 쿠폰`) + "비가 온다는 사실만으로는 보상 대상이 아닙니다" 원문 주입 확인.

### #2 `"결제 후 바로 취소하면 환불되나요?"` → ✅ `refund` (refund-basic)
- **응답:** *"주문 상태가 CREATED 또는 ACCEPTED인 경우 전액 환불 가능. 조리 시작(COOKING) 이후 단순 변심은 어렵고, 카드 결제는 최대 7영업일."* → 조리 전/후 구분 + 카드 취소 기간 포함.
- (관찰) 기대했던 `cancel-policy`가 아니라 `refund-basic`이 Top. "환불되나요?"가 의미상 환불 정책에 더 가까워서다 — 임계값 0.5를 통과한 건 refund-basic 1건뿐.

### #3 `"쿠폰 중복 사용되나요?"` → ✅ `coupon` (coupon-faq)
- **응답:** *"할인 쿠폰은 1회 1매. 할인+배달비 쿠폰은 동시 사용 가능, 할인+할인은 불가."* → 원문 그대로.

### #4 `"사장님 전화번호 알려주세요"` → ✅ 거절 (전화번호 노출 0)
- **Context 블록: 비어 있음** — `privacy` 문서가 임계값 0.5를 **통과하지 못함**(질문↔문서 유사도 낮음).
- **응답:** *"사장님 전화번호는 제공하지 않습니다. 가게 연락이 필요하시면 앱에 등록된 대표 번호를 이용해 주세요."*
- **핵심 증명:** 거절은 **RAG가 아니라 시스템 프롬프트 `[금지]` 규칙**이 만들었다. Context가 비어도 전화번호는 노출되지 않았다 → **2중 방어 중 프롬프트 레이어가 실제 안전망**. (입력 토큰도 1861로 가장 작음 = 주입된 정책 없음)

### #5 Memory+RAG 협업 (2턴, 세션 `memo-rag`) → ✅
원본: [raw/stage1-scenario-5-memory-rag.txt](./raw/stage1-scenario-5-memory-rag.txt)

| 턴 | 입력 | Memory | RAG | Tool | 응답 |
|---|---|---|---|---|---|
| 1 | "2024-1234 배달 어디쯤?" | (빈 세션) | (비어있음) | `getDeliveryStatus(2024-1234)` | "역삼역 사거리 근처, 예상 오후 4:09" |
| 2 | "아까 그 주문 환불 돼요?" | "아까 그 주문"→**2024-1234 복원** | `refund-basic`+`cancel-policy` 주입 | (없음) | "2024-1234는 **아직 배달 완료 안 됨 → 지금 환불 불가**, 배달 완료 후 **24시간 이내**" |

`/api/v1/session/memo-rag/messages`로 4개 메시지(USER/ASSISTANT×2) 저장 + "아까 그 주문"이 2024-1234로 이어진 것 확인. Memory(주문번호 복원)와 RAG(환불 정책 주입)가 **다른 일을 같은 체인에서** 맞물렸다.

## 설계 결정 문서

### 1) 왜 청크 800 / min 350인가?
배달 정책 문서는 **조항 단위로 이미 짧게 쪼개져** 있다(각 300~700토큰). 800/350이면 한 조항(예: 지연 보상 표 전체)이 **잘리지 않고 한 청크**에 담긴다 → 표의 모든 행이 함께 검색된다(2단계에서 이게 정확도의 핵심임을 증명). `min 350`은 너무 작은 꼬리 청크를 앞 청크에 병합해 의미 없는 조각을 막는다.
**다른 도메인이라면?** "블로그 글/장문 PDF"는 한 문서가 수천 토큰이고 주제가 섞여 있어, 800은 너무 커서 유사도가 뭉툭해진다 → **300~500 + 오버랩 20~30%** 로 더 공격적으로 쪼개야 한다.

### 2) 왜 Top-K = 4인가?
정책 문서가 **7건**(청크도 7개)뿐인 작은 코퍼스.
- `K=1`: "환불+지연" 같은 **복합 질문**에서 한쪽 정책만 잡혀 답이 반쪽.
- `K=10`: 7건짜리 코퍼스에 10건을 요구하면 임계값 위 **무관 청크까지** 끌어와 입력 토큰만 부풀고 노이즈.
- `K=4`: `refund-basic + refund-after-delivered`처럼 한 주제의 2~3청크를 동시에 담기 충분(실측 시나리오 2·4에서 2건 동반 검색). 7건 대비 과하지 않다.

### 3) 왜 `QuestionAnswerAdvisor.order(20)`인가? (시나리오 5로 설명)
`memory(10) → rag(20) → performance(100)`. 시나리오 5의 2턴 *"아까 그 주문 환불 돼요?"* 에서:
**Memory(10)가 먼저** 이전 턴(2024-1234, 배달중)을 프롬프트에 복원해야 → **RAG(20)가 그 복원된 맥락으로 검색**해 환불 정책을 찾는다. 순서를 뒤집으면(3단계 실험) RAG가 "아까 그 주문 환불 돼요?"라는 **빈약한 대명사 질문**만 보고 검색해 Context가 **0건**이 된다. `performance(100)`는 가장 바깥에서 최종 입력 토큰을 측정해야 하므로 마지막.

### 4) `similarityThreshold=0.5`의 근거
qwen3-embedding 기준 도메인 질문은 정책 청크와 0.55~0.7, 도메인 밖("오늘 점심")은 0.5 미만으로 떨어지는 경계가 0.5 부근.
- **너무 낮으면(0.3):** "오늘 점심"에도 무관 정책이 Top-K에 끼어 LLM이 그걸 근거라고 **오해→환각**.
- **너무 높으면(0.7~0.8):** 짧은 구어체("쿠폰 돼요?")나 시나리오 4(privacy)처럼 정답/관련 문서도 탈락 → Context 빈약.
- 0.5는 "출발점"이며 임베딩 모델 바뀌면 다시 측정해야 함(3단계에서 분포 실험).

---

# 2단계 — 청킹 전략 실험 A/B/C (25점)

각 실험 전 `TRUNCATE TABLE vector_store;` 후 청크 크기를 바꿔 재기동·재적재. 동일 5질문 시퀀스(마지막은 도메인 밖).

## 정량 비교표 (실측)

| 실험 | chunkSize / min | `vector_store` row 수 | 평균 입력 토큰(5턴) | 답변 품질 |
|---|---|---|---|---|
| **A** | 800 / 350 (기준) | **7** | **2382** | 만족 (5/5 정확) |
| **B** | 100 / 40 (극소) | **49** | **2058** | 애매~불만족 (수치 조각남·환각 발생) |
| **C** | 2000 / 800 (큼) | **7** | **2382** | 만족 (A와 동일) |

원본: [raw/stage2-A-800.txt](./raw/stage2-A-800.txt) · [B-100](./raw/stage2-B-100.txt) · [C-2000](./raw/stage2-C-2000.txt)

**품질 기준(자가 정의):** 만족 = 원문 수치/조건을 정확히 인용 · 애매 = 방향은 맞지만 수치 누락/뭉갬 · 불만족 = 틀린 수치/환각.

### 관찰 ① — C(2000)가 A(800)와 row 수·토큰이 **완전히 동일**한 이유
정책 문서 7건이 **모두 800토큰 미만**이라, 800으로 쪼나 2000으로 쪼나 **각 문서가 1청크**다 → 인덱스가 byte 단위로 같다(토큰 2382 동일). 즉 **chunkSize는 "상한"일 뿐, 문서가 그보다 작으면 아무 영향이 없다.** 숙제가 기대한 "C의 row<A" 와 "유사도 뭉툭"은 **문서가 chunkSize보다 클 때만** 나타난다(예: 2000토큰짜리 리뷰 묶음을 한 청크로 임베딩하면 여러 주제가 섞여 질문 벡터와의 유사도가 뭉툭해진다). → 우리 코퍼스에서는 **그 효과를 재현할 수 없음을 정직하게 기록**한다.

## 실패 관찰 ① — 청크가 너무 작을 때(B=100): 표가 잘려 환각

**동일 질문 / 동일 모델 / 동일 임계값, 청크 크기만 다름:** `"비 오는 날 30분 지연되면 얼마 보상받나요?"`
원본: [B 환각](./raw/stage2-B-100-fragmentation.txt) · [A/C 정답](./raw/stage2-A-800-fragmentation-contrast.txt)

**B(100) — Top-K에 올라온 Context (조각남):**
```
| 지연 범위 | 보상 |
|---|---|
| 예상 시간 + 10분 이내 | 보상 대상 아님 (정상 범위) |     ← 표가 첫 행 뒤에서 잘림!
- "비 오는 날"이라는 이유만으로는 보상이 어려울 수 있습니다. ...
- **배달 지연 과도 (60분 이상)**: ...
```
👉 `+30~59분 → 배달비 전액 환불 또는 3,000원 쿠폰` **행이 청크 경계에서 잘려 Context에 없음.**
**B 응답(환각):** *"30분 지연되면 보상은 **1,000원 쿠폰**을 제공합니다."* ❌
→ 1,000원은 **11~29분 구간** 값. 정답 행이 사라지자 LLM이 옆 구간 값으로 **때워버림.**

**A·C(800/2000) — 표 전체가 한 청크:**
```
| 예상 시간 + 11~29분 | 1,000원 쿠폰 |
| 예상 시간 + 30~59분 | 배달비 전액 환불 또는 3,000원 쿠폰 |   ← 정답 행 존재
| 예상 시간 + 60분 이상 | 전액 환불 |
```
**A·C 응답(정답):** *"30분 이상 지연된 경우 **배달비 전액 환불 또는 3,000원 쿠폰** 중 선택 가능합니다."* ✅

> **결론:** 청크를 잘게 쪼개면 검색 정밀도가 올라간다는 통념과 반대로, **표/리스트 같은 구조화 데이터는 잘게 쪼개면 "수치-맥락"이 분리되어 환각이 난다.** B는 평균 토큰(2058)이 A(2382)보다 낮지만, 그 절약이 **틀린 답**을 만들었다.

## 실패 관찰 ② — Fallback 없는 환각 (`[정책 인용 규칙]` 제거)

`BaedalPrompt`의 `[정책 인용 규칙]` 섹션을 **통째로 주석 처리**하고 도메인 밖 질문을 보냄.
원본 비교: [Fallback 정상](./raw/stage2-A-800.txt) (Q5) vs 아래 제거 상태.

| 질문 | `[정책 인용 규칙]` **있음** | `[정책 인용 규칙]` **제거** |
|---|---|---|
| "오늘 점심 뭐 먹을까요?" | *"저는 주문·배달·취소·환불·쿠폰 관련 상담만 도와드리고 있어요."* ✅ | *"오늘 점심 메뉴를 **추천해 드릴 수 있습니다.** 선호하는 종류가 있으신가요?"* ❌ |
| "치킨이랑 피자 중 뭐가 더 맛있어요?" | (범위 안내) | *"개인 취향에 따라 다릅니다..."* ❌ 잡담 |

**두 경우 모두 Context는 비어 있었다**(임계값 0.5가 무관 문서를 다 걸러냄). 그런데도 규칙을 빼니 LLM은 잡담에 응했다.
👉 **"similarityThreshold만으로 환각을 막을 수 있는가?" → 못 막는다.** 임계값은 *"무관 문서 제거"* 만 한다. *"LLM이 빈 Context 위에서 제멋대로 답하는 것"* 은 **시스템 프롬프트 Fallback 규칙**만 막을 수 있다. **2중 방어가 둘 다 필요**하다.

## 설계 결정 문서

- **이 도메인 최적 청크 크기:** **800**. 정책 조항(특히 보상 표)이 통째로 한 청크에 담겨 수치-맥락이 보존되고, 토큰도 과하지 않다. B(100)는 표를 잘라 환각, C(2000)는 효과 없음(문서가 더 작음).
- **오버랩을 0으로 하면?** 청크 경계에 걸친 문장이 어느 쪽에도 온전히 안 들어가 "기상 특보 시 예상 시간 +" 와 "30분" 이 서로 다른 청크로 갈라질 수 있다. 오버랩은 경계 근처 맥락을 양쪽이 공유하게 해 이 단절을 완화한다(우리 코퍼스는 1문서=1청크라 오버랩이 발동 안 했지만, B처럼 쪼개지는 순간 필요).
- **"사용자 리뷰 10만 건"이라면?** ①청크: 리뷰 1건=1청크(이미 짧음)거나, 짧은 리뷰는 묶어 임베딩. ②중복 방지: faqId 대신 **내용 해시(SHA-256)** 로 "바뀐 것만 재적재". ③재인덱싱: 기동 시 1회가 아니라 **신규 리뷰 유입 배치/스트리밍**으로 증분 색인.
- **임계값만으로 환각 차단? →** 위 실패 관찰 ②가 답: **불가**. 임계값(무관 문서 제거)+프롬프트 Fallback(빈 Context 위 환각 제거) 2중 방어.

---

# 3단계 — Memory + RAG + Advisor 순서 실험 (20점)

동일 2턴 대화를 **정상 순서**와 **뒤바꾼 순서**로 각각 실행.
원본: [정상](./raw/stage3-normal-order.txt) · [고장](./raw/stage3-broken-order.txt)

## 2턴 대화에서 Memory / RAG가 각각 한 일 (정상 순서)

**2턴 `"아까 그 주문 환불 돼요?"` 의 최종 Ollama 요청 구조:**
```
SYSTEM(정책 프롬프트)
USER     "주문번호 2024-1234 배달 어디?"          ┐ Memory(10)가 주입한
ASSISTANT "...역삼역 사거리..."                    │ 이전 턴 메시지
TOOL      {orderId:2024-1234, status:DELIVERING}  ┘
USER     "아까 그 주문 환불 돼요?\n\nContext: # 환불 기본 정책 ... # 주문 취소 정책 ..."   ← RAG(20) 주입
```
- **Memory가 한 일:** "아까 그 주문"의 정체(2024-1234)와 배달 상태를 프롬프트에 되살림.
- **RAG가 한 일:** `refund-basic` + `cancel-policy` 정책 원문을 Context로 주입.
- **LLM 최종:** *"2024-1234는 아직 배달 완료 전이라 지금 환불 불가, 배달 완료 후 24시간 이내."* (Memory∩RAG 결합)

## Advisor 순서 뒤바꿈 관찰 표 (핵심)

`RagConfig`에서 `order(20)`→`order(5)`로 바꿔 **RAG가 Memory보다 먼저** 실행되게 함.

| 관찰 포인트 | `memory(10)→rag(20)` **정상** | `rag(5)→memory(10)` **고장** |
|---|---|---|
| 2턴 Context에 들어간 정책 카테고리 | `환불 기본 정책` + `주문 취소 정책` (2건) | **(비어 있음 — 0건)** |
| Context가 현재 주문(1234)과 관련 있나 | 예 (환불/취소 정책) | N/A (Context 없음) |
| LLM 응답의 정책 근거 | 정책 원문(24시간 등) 인용 | 정책 근거 없이 Tool 데이터+일반론으로 때움 |
| 2턴 입력 토큰 | 2867 | 6051 (RAG 실패 → Tool 재호출로 오히려 증가) |

**왜 고장 순서에서 Context가 0건인가:** RAG가 **먼저** 실행되면 Memory가 아직 "2024-1234"를 복원하기 전이라, RAG는 **`"아까 그 주문 환불 돼요?"` 라는 짧고 모호한 대명사 질문**만 임베딩한다. 이 질문은 어떤 정책 청크와도 유사도 0.5를 넘지 못해 **Context가 비어버린다.** 정상 순서에서는 Memory가 먼저 1234·배달중 맥락을 프롬프트에 깔아둔 뒤 RAG가 검색하므로 환불/취소 정책을 찾아낸다.

## 설계 결정 문서

- **왜 Memory가 RAG보다 먼저여야 하나 (프롬프트 조립 순서 관점):** Advisor 체인은 order 오름차순으로 프롬프트를 **누적 조립**한다. Memory(10)가 먼저 이전 턴 메시지를 프롬프트에 넣어 질문의 맥락("그 주문" = 2024-1234)을 복원해두면, RAG(20)는 그 **맥락이 깔린 상태**에서 검색해 올바른 정책을 가져온다. 순서가 반대면 RAG는 맥락 없는 대명사 질문만 보고 빈손으로 돌아온다(위 표).
- **반대 순서가 더 나은 경우가 있나?** 있다. **Memory에 개인정보가 쌓이는 경우** — 이전 턴에 고객이 카드번호·주소를 말했다면, Memory를 먼저 붙인 뒤 RAG가 그 맥락째로 임베딩/외부 벡터DB에 보내는 건 **정보 유출 위험**이다. 이때는 RAG(또는 입력 필터)를 먼저 두어 **민감 정보를 임베딩 전에 마스킹/차단**하는 게 낫다. (Round 5 Guardrail 주제)
- 실험 후 `order(20)` 으로 **복원 완료**.

---

# 4단계 — Observability + AI 코드 리뷰 (15점)

## RAG 주입의 토큰 비용 (실측)

동일 질문 `"배달 완료 후에도 환불 받을 수 있나요?"`. **Memory/RAG 주입 비용만 격리**하기 위해 이 측정에 한해 `defaultTools(orderTools)`를 빼고 3체인을 헤더(`X-Advisor-Mode`)로 골라 측정.
원본: [raw/stage4-token-sweep-abc.txt](./raw/stage4-token-sweep-abc.txt)

| 조건 | Advisor 체인 | 입력 토큰 | 출력 토큰 | 응답(ms) | RAG Context |
|---|---|---|---|---|---|
| (a) Memory·RAG 없음 | `performance` | **1179** | 58 | 2431 | 0건 |
| (b) Memory만 (빈 세션) | `memory, performance` | **1179** | 54 | 1600 | 0건 |
| (c) Memory + RAG | `memory, rag, performance` | **2081** | 87 | 6463 | **2건** |

- (a) ≈ (b): **빈 메모리는 입력 토큰을 거의 안 늘린다**(주입할 과거가 없음).
- **(c) − (a) = +902 입력 토큰** = RAG가 주입한 정책 원문(`refund-after-delivered` + `refund-basic`). 이게 **"정확도를 토큰으로 산다"** 의 실체.

> **부가 관찰:** Tool을 켠 상태로 같은 질문을 (b)로 돌리면 입력 토큰이 **3841**까지 뛴다 — RAG 근거가 없자 LLM이 `getOrderDetail(2024-1234)`(예시 번호 환각)을 호출해 Tool 결과까지 프롬프트에 들어가서다. **즉 RAG는 토큰을 더하기도 하지만, 엉뚱한 Tool 호출을 억제해 토큰을 줄이기도 한다.**

## (c) 조건에서 실제 주입된 `Context:` 블록 전문

원본: [raw/stage4-c-memory-rag.txt](./raw/stage4-c-memory-rag.txt) — `refund-after-delivered`(배달 완료 후 환불 정책) → `refund-basic`(환불 기본 정책) 순으로 2개 문서가 통째로 삽입됨.

```
Context information is below, surrounded by ---------------------
---------------------
# 배달 완료 후 환불 정책
... 1.메뉴 누락 2.오배송 3.품질 불량 4.수량 오류 ...
## 접수 시한
- 배달 완료 후 24시간 이내 접수만 유효합니다.
... 필수 증빙(사진) ... 부분 환불 ...
# 환불 기본 정책
... 조리 시작 전 취소(CREATED/ACCEPTED) 전액 ... 카드 최대 7영업일 ...
---------------------
Given the context and provided history information and not prior knowledge,
reply to the user comment. If the answer is not in the context, inform the user that you can't answer the question.
```
응답이 *"메뉴 누락/오배송/품질 불량, 24시간 이내, 증빙 사진"* 을 정확히 인용 → 입력 토큰 증가분(+902)이 곧 이 정책 원문임이 확인된다.

## AI 코드 리뷰 — 프로덕션 결함 찾기

프롬프트 *"Spring AI 1.0으로 RAG 기반 FAQ 챗봇을 만들어줘. PgVector와 OpenAI 임베딩을 써."* 에 AI가 흔히 내놓는 **전형적 패턴**(아래)을 리뷰했다.

### AI 생성 원본 코드 (전형적 패턴)
```java
@Configuration
class RagConfig {
    @Bean
    QuestionAnswerAdvisor qa(VectorStore vs) {
        return new QuestionAnswerAdvisor(vs);          // topK·threshold 기본값
    }
}

@Service
@RequiredArgsConstructor
class FaqIndexer {
    private final VectorStore vectorStore;
    @PostConstruct
    void load() throws IOException {
        var text = Files.readString(Path.of("faq.md"));
        vectorStore.add(List.of(new Document(text)));  // 통문서 1개, 분할·metadata 없음
    }
}
// application.yml: openai embedding(text-embedding-3-small, 1536d) +
//                  pgvector initialize-schema: true, dimensions: 1536
// 시스템 프롬프트: "친절한 FAQ 봇이야" (Fallback 규칙 없음)
```

### 결함 3개 + 이번 수업 방식의 개선안

| # | 결함 | 무슨 일이 나나 | 개선 (이번 수업에서 한 방식) |
|---|---|---|---|
| **1** | **청크 무분할 + Top-K/임계값 무설정** (`new Document(전체)`, `new QuestionAnswerAdvisor(vs)`) | 통문서가 1벡터로 뭉개져 유사도 뭉툭 + 기본 topK로 무관 문서까지 프롬프트 폭증, 임계값 없어 환각 | `TokenTextSplitter(800,350,...)`로 청킹 + `SearchRequest.builder().topK(4).similarityThreshold(0.5)` — 우리 `RagConfig` |
| **2** | **중복 적재 방지 없음** (`@PostConstruct`에서 무조건 `add`) | 앱 재기동마다 같은 FAQ가 2배씩 쌓여 검색 결과 중복·오염 | `alreadyLoaded(faqId)` = `filterExpression("faqId == '...'")` 로 스킵, `ApplicationRunner`로 schema 준비 후 실행 — 우리 `KnowledgeLoader` (실측 "스킵 7건") |
| **3** | **Fallback 미설계 + 출처 metadata 없음** | 검색 0건이어도 LLM이 상상으로 답(범위 밖 잡담), "어느 정책 근거인지" 추적 불가 | `[정책 인용 규칙]`에 고정 Fallback 문구 + 임계값 2중 방어, `metadata{faqId,title,category}` 주입해 출처 로깅 가능 — 우리 `BaedalPrompt`/`KnowledgeLoader` |

**보너스 결함:** ④ **임베딩 차원 불일치 위험** — 인덱싱을 OpenAI(1536d)로 해놓고 나중에 검색을 로컬 임베딩(qwen3 1024d)으로 바꾸면 벡터 공간이 안 맞아 **조용히 쓰레기 결과**. → 우리는 인덱싱·검색 **둘 다 `qwen3-embedding:0.6b`(1024d)** 로 통일하고 `application.yml dimensions: 1024`와 일치시킴. ⑤ **`initialize-schema:true`를 프로덕션에 방치** → 운영은 Flyway/Liquibase로 분리해야.

---

# 공통 — 학습 기록 (10점)

## 내가 배운 것
RAG의 **2 파이프라인 분리**(인덱싱=기동 시 1회 / 검색=매 요청)가 머리에 그려진 게 가장 컸다. 특히 **"검색 품질의 경계는 코드가 아니라 4개의 숫자/문장(청크·Top-K·임계값·Fallback)에서 결정된다"** 는 걸 손으로 만졌다. 백미는 **청크 100 실험**이었다 — 통념(잘게 쪼갤수록 정밀)과 반대로, **보상 표가 청크 경계에서 잘려 LLM이 1,000원/3,000원을 헷갈리는 환각**을 직접 봤다. 또 **임계값과 Fallback이 막는 환각이 서로 다르다**는 것(임계값=무관 문서 제거, Fallback=빈 Context 위 잡담 제거)을 `[정책 인용 규칙]` 제거 실험으로 체감했다. 마지막으로 **Tool과 RAG가 같은 체인에서 충돌**(RAG는 맞았는데 예시 번호 Tool 호출이 답을 덮어씀)하는 걸 보고, "Advisor를 더한다"가 공짜가 아님을 배웠다.

## 의문점
- **한국어 임베딩 품질을 어떻게 정량 측정**할까? 지금은 "정답 문서가 Top-K에 드나"를 눈으로 봤지만, recall@k / MRR 같은 지표를 작은 골든셋으로 자동화하고 싶다.
- 시나리오 4(`privacy`)와 시나리오 2(`cancel-policy`)처럼 **"의미상 가까운데 임계값에 못 미쳐 빠지는" 정답 문서**를 어떻게 살릴까? (질문 재작성? 하이브리드 키워드+벡터?)
- **출처(metadata.title)를 응답에 자동 노출**하려면 `QuestionAnswerAdvisor` 기본 템플릿을 커스터마이즈해야 하는데, 깔끔한 방법은?
- 정책 문서가 자주 바뀔 때 **재인덱싱 트리거**(해시 비교 배치 vs 파일 워처 vs 관리 API)의 실무 베스트는?

## Round 5 (Guardrail)에 시도하고 싶은 것
1. **검색 0건 → 즉시 상담원 연결 Tool 호출**하는 Guardrail Advisor. 이번에 본 "Context 비어도 LLM이 잡담"을 프롬프트가 아니라 **체인 레벨**에서 차단.
2. **입력 필터 Guardrail:** "사장님 전화번호" 같은 `[금지]` 질문은 **임베딩/검색 자체를 건너뛰는** 선제 필터(이번엔 검색은 돌고 프롬프트가 막았는데, 검색조차 낭비). 3단계에서 본 "Memory에 개인정보가 있으면 임베딩 전에 마스킹" 과도 연결.
3. **Tool×RAG 충돌 가드:** 이번 "실패 관찰 0"처럼 정책 질문에 주문 Tool이 끼어드는 걸, 의도 분류(정책 vs 주문)로 라우팅하는 Guardrail.

---

## 참조
- 소스: [`rag/`](../../src/main/java/com/baedal/support/rag) · [`BaedalPrompt.java`](../../src/main/java/com/baedal/support/BaedalPrompt.java)
- 원본 로그/캡처: [`raw/`](./raw)
- 정책 시드 문서: [`knowledge/`](../../src/main/resources/knowledge)
