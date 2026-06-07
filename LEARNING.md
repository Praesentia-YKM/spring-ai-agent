# round4-01 · RagConfig — RAG 설정·검색의 뼈대

> 이 worktree의 **초점 기능 ①**: `rag/RagConfig.java`의 빈 2개(`TokenTextSplitter`,
> `QuestionAnswerAdvisor`)와 검색 파라미터(`TOP_K`, `SIMILARITY_THRESHOLD`)를 직접 구현한다.
> 나머지 ②③④는 TODO 상태 — 이 브랜치에서는 ①만 완성하고 기동·검증한다.

## 0. 사전 요건

```bash
docker compose up -d                 # pgvector :5432 (healthy 확인: docker ps)
ollama pull qwen2.5                  # chat
ollama pull qwen3-embedding:0.6b     # embedding, 1024차원
ollama list                          # 두 모델 보이는지
```

## 1. 학습 포인트 (개념)

- **RAG 3구성요소의 역할 분리**
  - `EmbeddingModel` (텍스트→1024차원 벡터, Ollama 자동구성) — "의미 API"
  - `VectorStore`(PgVector) — `add()` / `similaritySearch(SearchRequest)`
  - `QuestionAnswerAdvisor` — 질문 임베딩→검색→프롬프트 `Context:` 주입을 **한 줄로** 자동화
- **TokenTextSplitter(청킹)** — 긴 문서를 청크로 쪼개 각각 임베딩. 크기/오버랩이 검색 품질의 핵심 튜닝값.
- **Advisor order** — `QuestionAnswerAdvisor.order(20)`: Memory(10) 뒤, Performance(100) 앞. 낮을수록 먼저.

## 2. 초점 TODO (RagConfig)

| TODO | 내용 | 권장 출발값 |
|------|------|-------------|
| A | `TOP_K` | 4 (정책 문서 7건) |
| B | `SIMILARITY_THRESHOLD` | 0.5 (qwen3 임베딩 기준 도메인 질문 돌려보고 조정) |
| C | `tokenTextSplitter()` 빈 | `new TokenTextSplitter(800, 350, 5, 10_000, true)` |
| D | `questionAnswerAdvisor(VectorStore)` 빈 | `SearchRequest`(topK·threshold) + `QuestionAnswerAdvisor.builder(vs).searchRequest(..).order(20).build()` |

### 구현 힌트 (시그니처)

```java
// C
return new TokenTextSplitter(800, 350, 5, 10_000, true);
// (chunkSize, minChunkSizeChars, minChunkLengthToEmbed, maxNumChunks, keepSeparator)

// D
SearchRequest searchRequest = SearchRequest.builder()
        .topK(TOP_K).similarityThreshold(SIMILARITY_THRESHOLD).build();
return QuestionAnswerAdvisor.builder(vectorStore)
        .searchRequest(searchRequest)
        .order(20)            // Memory(10) 뒤, Performance(100) 앞
        .build();
```

## 3. 설계 결정 질문 (README에 본인 언어로)

- 왜 **청크 800 / min 350**인가? 배달 정책 문서가 조항 단위로 짧게 끊겨 있다는 점을 근거로.
  "블로그 글·장문 PDF"라면 어떻게 바꿀까?
- 왜 **Top-K = 4**인가? 문서 7건에서 1 / 4 / 10 중 4를 고른 근거.
- 왜 **order(20)**인가? memory(10)→rag(20)→performance(100) 순서가 필요한 이유. (3단계에서 직접 관찰)
- **similarityThreshold=0.5**: 너무 낮으면/높으면 각각 무슨 문제? (실험은 2·3단계)

## 4. 검증 (직접 확인)

> ⚠️ 구현 **전**에는 RagConfig 빈이 `null`을 반환 → 의존성 주입 실패로 **기동되지 않는다**(의도된 상태).
> ①을 완성하면 비로소 기동된다 — 이것이 ① 완료의 1차 증거다.

```bash
./gradlew bootRun
# 기대 기동 로그:
#  - DataSource/PgVector 초기화, vector_store 테이블 생성 (initialize-schema=true)
#  - 애플리케이션 정상 기동 (BeanCreation 에러 없음)
#  - [KnowledgeLoader] RAG 시드 완료 — 신규 0건 ...  (② 미구현이라 적재 0 — 정상)
```

```bash
# pgvector 연결 + 테이블/확장 생성 확인
docker exec -it baedal-pgvector psql -U baedal -d baedal -c "\dx"          # vector 확장
docker exec -it baedal-pgvector psql -U baedal -d baedal -c "\dt vector_store"  # 테이블 존재
```

## 5. 자가 점검 체크리스트

- [ ] `tokenTextSplitter`, `questionAnswerAdvisor` 두 빈이 `null`이 아님
- [ ] `questionAnswerAdvisor`에 `.order(20)` 명시 (기본값 의존 X)
- [ ] `SearchRequest`에 `topK`와 `similarityThreshold` **둘 다** 설정
- [ ] `./gradlew bootRun` 정상 기동 (이전엔 null 빈으로 실패)
- [ ] pgvector에 `vector_store` 테이블 + `vector` 확장 생성됨
- [ ] (다음 브랜치 `round4-02`에서 이어서) 실제 문서 적재

## 6. 다음 단계

이 브랜치에서 ①을 끝냈으면 → `round4-02-knowledge-loader` worktree로 이동.
거기엔 **① 레퍼런스가 이미 완성**돼 있고, 초점은 ② KnowledgeLoader(인덱싱)다.
