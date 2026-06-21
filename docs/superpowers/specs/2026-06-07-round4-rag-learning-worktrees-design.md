# Round 4 RAG 학습 환경 설계 — 누적형 worktree + 브랜치별 LEARNING.md

- 작성일: 2026-06-07
- 브랜치: `round4` (베이스) + `round4-01..04` (기능별 worktree)
- 목적: round4 발표자료(RAG 미션)의 학습 포인트를, 내 round3 작업물 위에 **학습용 스캐폴드**로 얹어
  기능별 worktree에서 하나씩 직접 구현·검증하고, 이후 tech-deep-interview로 점검한다.

## 1. 배경과 원칙

- **베이스는 내 round3**(flat 구조, `com.baedal.support` 직속). round3 작업물은 보존한다.
- round4 레퍼런스(`upstream/round4`, 강사 jeonggyu 작성)는 **서브패키지 구조 + RAG 스타터(TODO)**.
  구조를 통째로 가져오면 flat 클래스와 중복되어 깨지므로, **RAG 신규 자료만** flat 위에 이식한다.
- `rag/` 패키지의 신규 파일은 `com.baedal` 서브패키지에 의존하지 않으므로 flat 구조에 그대로 얹힌다.
- **학습용 스캐폴드**: 초점 기능은 TODO로 남겨 내가 직접 구현한다. 레퍼런스의 설계 주석·설계결정
  질문(=멘토 조언)은 보존해 학습 가치를 유지한다.

## 2. 학습 포인트 (= 기능 단위)

| # | 기능 | 핵심 파일 | TODO |
|---|------|-----------|------|
| ① | RAG 설정·검색 | `rag/RagConfig.java` | TOP_K, SIMILARITY_THRESHOLD, TokenTextSplitter 빈, QuestionAnswerAdvisor 빈(order=20) |
| ② | 인덱싱 파이프라인 | `rag/KnowledgeLoader.java` | Document 변환·청킹·`vectorStore.add()`, `alreadyLoaded()` 중복방지 |
| ③ | Advisor 체인 통합 | `AssistantController`, `SupportController` | `defaultAdvisors(memory, rag, performance)` 순서 |
| ④ | 환각 방지(Fallback) | `BaedalPrompt` | `[정책 인용 규칙]` (근거만/Fallback/원문 수치/우선순위/범위 밖) |

관찰/실험 학습 포인트(④ 완성 후 별도 worktree): ⑤ 청킹 실험(100/800/2000) ⑥ Advisor 순서(20 vs 5) ⑦ 토큰 비용 관찰.

## 3. 아키텍처 (완성 시 데이터 흐름)

```
[인덱싱] knowledge/*.md → KnowledgeLoader(ApplicationRunner) → TokenTextSplitter 청킹
         → EmbeddingModel(qwen3-embedding:0.6b, 1024d) → PgVector(vector_store)  ※ 멱등(중복방지)
[검색]   질문 → MessageChatMemoryAdvisor(10) → QuestionAnswerAdvisor(20) → LLM → PerformanceLoggingAdvisor(100)
              "아까 그 주문" 복원         knowledge 유사도 검색 → Context 주입
```

## 4. 베이스 `round4` 스캐폴드 (1커밋)

- 신규(레퍼런스 그대로): `rag/{FaqDocument(완성), RagConfig(TODO A–D), KnowledgeLoader(TODO E–F)}`,
  `resources/knowledge/*.md` 7건, `docker-compose.yml`(pgvector pg16).
- 수정: `build.gradle`(+pgvector 3종), `application.yml`(+datasource·embedding·vectorstore.pgvector, 로깅 병합),
  `AssistantController`/`SupportController`(+`QuestionAnswerAdvisor ragAdvisor` 주입 + TODO, 체인 미연결),
  `BaedalPrompt`(+`[정책 인용 규칙]` TODO, 기존 `[응답 포맷]` 카테고리 유지).
- 미도입: 레퍼런스 `domain/`·`tool/`·`memory/` 서브패키지(내 flat 존재), `JdbcChatMemoryExample`.
- ⚠️ 베이스는 RagConfig 빈이 `null`이라 **기동되지 않는다**(의도된 상태). ① 구현 후 기동된다.

## 5. 누적형 worktree

| 브랜치 | 폴더 | 사전 완성(레퍼런스) | 초점 TODO | 검증 |
|--------|------|----------------------|-----------|------|
| `round4-01-rag-config` | `../spring-ai-agent-wt/round4-01-rag-config` | 없음 | ① RagConfig | 기동·빈 생성·pgvector 연결 |
| `round4-02-knowledge-loader` | `…/round4-02-knowledge-loader` | ① | ② KnowledgeLoader | "신규 7건"/재기동 "스킵 7건"·psql row |
| `round4-03-advisor-chain` | `…/round4-03-advisor-chain` | ①② | ③ 컨트롤러 체인 | 응답에 정책 원문·Context 로그 |
| `round4-04-policy-prompt` | `…/round4-04-policy-prompt` | ①②③ | ④ 정책 인용 규칙 | 도메인 밖 Fallback 동작 |

- 사전 완성 코드는 발표자료·리뷰가이드의 **모범 답안**을 사용 → 각 브랜치가 단독 기동·검증 가능.
- 각 브랜치 루트에 `LEARNING.md`: 개념 · 초점 TODO · 설계결정 질문 · 구현 힌트 · 검증 커맨드(curl/psql/기동로그) · 자가 점검 · 사전요건.

## 6. 검증 (수동, 발표자료 기준)

- 사전요건: `docker compose up -d`(pgvector :5432), `ollama pull qwen2.5` + `ollama pull qwen3-embedding:0.6b`.
- 단위 테스트는 두지 않는다(학습 미션은 curl/psql/DEBUG 로그로 관찰). LEARNING.md에 명령을 명시한다.
- 핵심 검증: 5종 시나리오(환불/취소/쿠폰/개인정보 거절/Memory+RAG), 재기동 멱등(스킵 7건), Fallback(도메인 밖).

## 7. 비범위 (YAGNI)

- 실험용 worktree(⑤⑥⑦)는 ④ 완성 후 별도 생성한다(지금은 만들지 않음).
- 원격 push 안 함(로컬). README round4 섹션은 사용자가 직접 작성(학습 산출물).
