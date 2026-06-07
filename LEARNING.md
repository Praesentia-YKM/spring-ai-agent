# round4-02 · KnowledgeLoader — 인덱싱 파이프라인

> **사전 완성(레퍼런스)**: ① `RagConfig` (TokenTextSplitter + QuestionAnswerAdvisor) ✅
> **이 worktree의 초점 기능 ②**: `rag/KnowledgeLoader.java`의 TODO E·F —
> 문서를 청킹해 VectorStore에 적재하고, 재기동 시 중복 적재를 막는다.

## 0. 사전 요건

```bash
docker compose up -d
ollama pull qwen2.5 && ollama pull qwen3-embedding:0.6b
```

## 1. 학습 포인트 (개념)

- **인덱싱 파이프라인**: `knowledge/*.md` → `FaqDocument` → Spring AI `Document`(+metadata) → `TokenTextSplitter`로 청킹 → `EmbeddingModel`로 벡터화 → `VectorStore.add()`.
- **왜 `ApplicationRunner`인가** — `@PostConstruct`는 빈 초기화 단계라 VectorStore의 `initialize-schema`와 경쟁할 수 있다. `ApplicationRunner`는 컨텍스트 기동 완료 후 실행 → 안전.
- **metadata(`faqId`/`title`/`category`)를 심는 이유** — (1) 중복 방지 키, (2) 검색 결과 출처 추적, (3) 카테고리 필터.
- **중복 적재 방지** — VectorStore엔 "id로 한 건 조회"가 없다. `similaritySearch` + `filterExpression` + `similarityThresholdAll()`로 "있는지 yes/no"만 확인.

## 2. 초점 TODO (KnowledgeLoader)

| TODO | 위치 | 내용 |
|------|------|------|
| E | `run()` 루프 본문 | `FaqDocument`→`Document`(metadata) → `tokenTextSplitter.apply()` → `vectorStore.add(chunks)` → `loaded++` 로그 |
| F | `alreadyLoaded(faqId)` | `filterExpression("faqId == '...')` + `similarityThresholdAll()` 로 중복 판정 |

### 구현 힌트

```java
// E
Document doc = new Document(faq.id(), faq.content(),
        Map.of("faqId", faq.id(), "title", faq.title(), "category", faq.category()));
List<Document> chunks = tokenTextSplitter.apply(List.of(doc));
vectorStore.add(chunks);
loaded++;
log.info("[KnowledgeLoader] 적재 완료 — id={} / 청크={}개 / 카테고리={}", faq.id(), chunks.size(), faq.category());

// F
SearchRequest req = SearchRequest.builder()
        .query("정책").topK(1).similarityThresholdAll()
        .filterExpression("faqId == '" + faqId + "'")
        .build();
return !vectorStore.similaritySearch(req).isEmpty();
```

> ⚠️ 흔한 실수: 필터 문법은 SQL `=`가 아니라 **`==`**. `similarityThresholdAll()` 빠지면 기본 임계값에 막혀 항상 "없음"으로 판정→매번 재적재.

## 3. 설계 결정 질문 (README)

- 왜 `tokenTextSplitter.apply()`로 쪼개는가? 원본 1개로 넣으면 뭐가 달라지나?
- `vectorStore.add(chunks)`는 내부적으로 무엇을 하나? (힌트: EmbeddingModel 호출)
- 이 중복방지 방식의 프로덕션 한계는? (힌트: 문서 "내용이 바뀌었을 때"는 감지 못 함 — 해시 전략과 비교)

## 4. 검증 (직접 확인)

```bash
./gradlew bootRun
# 1차 기동 기대: [KnowledgeLoader] RAG 시드 완료 — 신규 7건 / 스킵 0건 / 총 7건
# 앱 종료 후 재기동 기대: 신규 0건 / 스킵 7건   ← 중복 방지(F) 증명
```

```bash
# pgvector 적재 확인 (카테고리 분포)
docker exec -it baedal-pgvector psql -U baedal -d baedal \
  -c "SELECT count(*), metadata->>'category' AS category FROM vector_store GROUP BY metadata->>'category';"
# 청크가 쪼개졌으므로 row 수 >= 7 (문서 7건 × 청크)
```

## 5. 자가 점검 체크리스트

- [ ] 1차 기동에서 `신규 7건` 로그
- [ ] 재기동에서 `스킵 7건` 로그 (중복 방지 동작)
- [ ] `vector_store`에 7개 카테고리(refund/cancel/coupon/delivery-delay/account) row 존재
- [ ] `Document` metadata에 `faqId`/`title`/`category` 들어감
- [ ] `filterExpression`에 `==` 사용, `similarityThresholdAll()` 포함

## 6. 다음 단계

② 완료 후 → `round4-03-advisor-chain` worktree. 거기엔 **①② 레퍼런스 완성**, 초점은 ③ 컨트롤러 Advisor 체인 연결이다.
