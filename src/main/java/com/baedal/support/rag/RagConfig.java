package com.baedal.support.rag;

import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 4주차 — RAG 설정 (Vector Store + 청킹 + QuestionAnswerAdvisor).
 *
 * <h3>구성 요소</h3>
 * <ul>
 *     <li>{@link VectorStore}: {@code PgVectorStore}가 자동 구성으로 주입된다
 *         ({@code spring-ai-starter-vector-store-pgvector} + {@code application.yml}).</li>
 *     <li>{@link TokenTextSplitter}: 긴 문서를 토큰 단위 청크로 쪼개는 Splitter.
 *         청크 크기와 오버랩은 "검색 정확도 vs 컨텍스트 보존"의 트레이드오프.</li>
 *     <li>{@link QuestionAnswerAdvisor}: 사용자 질문을 받아 자동으로 VectorStore를 검색하고,
 *         Top-K 결과를 프롬프트에 주입하는 Advisor.</li>
 * </ul>
 *
 * <h3>Advisor 체인 순서</h3>
 * <pre>
 *   MessageChatMemoryAdvisor   (order=10)   — 3주차: 이전 대화 이력 주입
 *   QuestionAnswerAdvisor      (order=20)   — 4주차: RAG 검색 결과 주입
 *   PerformanceLoggingAdvisor  (order=100)  — 1주차: 최종 호출 시간 집계
 * </pre>
 * Memory가 먼저 "아까 그 주문"의 orderId를 복원해야 RAG가 "그 주문의 환불 정책"을
 * 검색할 수 있다. 순서를 바꾸면 어떤 품질 저하가 생기는지 숙제 3단계에서 관찰한다.
 *
 * @see KnowledgeLoader FAQ/정책 문서를 VectorStore에 적재하는 ApplicationRunner
 */
@Configuration
public class RagConfig {

    // TODO [1단계-A] TOP_K 값을 결정하라.
    //
    // 이 값은 similaritySearch가 반환할 "상위 N건"이다.
    // 숙제 3단계에서 1 / 4 / 10 세 값으로 바꾸며 검색 품질/토큰 비용을 비교한다.
    //
    // 기본 출발점으로 4를 권장한다. 자신이 선택한 값과 근거를 README에 기록하라.
    //
    // 힌트:
    //   - 너무 작으면(예: 1): 관련 정책을 놓친다 — 복합 질문("환불 + 지연")에서 실패 관찰 가능.
    //   - 너무 크면(예: 10): 프롬프트에 정책 원문 10개가 들어가 입력 토큰이 폭증한다.
    //   - 배달 정책 문서 수(7건 내외)를 고려하라.
    private static final int TOP_K = 4; // TODO: 왜 이 값인지 README에 근거 기록

    // TODO [1단계-B] SIMILARITY_THRESHOLD 값을 결정하라.
    //
    // COSINE_SIMILARITY 기준 0.0 ~ 1.0. 이 값 미만의 검색 결과는 "관련 없음"으로 걸러진다.
    // 0.5는 대략 "주제가 같은 정도"를 의미하며, 도메인/임베딩 모델에 따라 조정이 필요하다.
    //
    // 숙제 3단계에서 0.3 / 0.5 / 0.7 세 값으로 실험한다.
    //
    // 힌트:
    //   - 너무 낮으면(0.3): 도메인 밖 질문("오늘 점심 뭐?")에도 무관 정책이 Top-K에 들어가
    //     LLM이 그걸 근거라고 오해해 환각을 만든다.
    //   - 너무 높으면(0.8): 정답 문서도 걸러져 Context가 비어 Fallback만 나온다.
    private static final double SIMILARITY_THRESHOLD = 0.5; // TODO: 왜 이 값인지 README에 근거 기록

    // TODO [1단계-C] TokenTextSplitter Bean을 등록하라.
    //
    // 요구사항:
    //   return new TokenTextSplitter(
    //       800,    // chunkSize: 청크 한 개의 목표 토큰 수
    //       350,    // minChunkSizeChars: 이보다 작으면 앞 청크에 병합
    //       5,      // minChunkLengthToEmbed: 이보다 짧으면 임베딩 제외
    //       10_000, // maxNumChunks
    //       true    // keepSeparator (문단 구분자 유지)
    //   );
    //
    // 숙제 2단계에서 chunkSize를 200 / 800 / 2000 세 값으로 실험한다.
    //
    // 설계 결정 질문 (README):
    //   - 배달 정책 문서는 조항 단위로 이미 쪼개져 있다. 이상적인 청크 크기는?
    //   - 만약 문서가 "사용자 리뷰 10만 건"이라면 청크 크기 선택이 어떻게 달라져야 하는가?
    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        // TODO: TokenTextSplitter 인스턴스 반환
        return null;
    }

    // TODO [1단계-D] QuestionAnswerAdvisor Bean을 등록하라.
    //
    // 요구사항:
    //   SearchRequest searchRequest = SearchRequest.builder()
    //           .topK(TOP_K)
    //           .similarityThreshold(SIMILARITY_THRESHOLD)
    //           .build();
    //
    //   return QuestionAnswerAdvisor.builder(vectorStore)
    //           .searchRequest(searchRequest)
    //           .order(20)  // Memory(10) 뒤, Performance(100) 앞
    //           .build();
    //
    // order(20)의 의미:
    //   - Advisor 체인에서 실행 순서를 결정한다. 낮을수록 먼저 실행된다.
    //   - Memory Advisor(order=10)가 먼저 대화 맥락을 붙인 후 RAG가 검색해야
    //     "아까 그 주문 환불 돼요?"에서 Memory가 orderId를 복원한 질문으로 검색이 된다.
    //
    // 설계 결정 질문 (README):
    //   - 만약 order를 바꿔 RAG(10) → Memory(20) 순서라면 어떤 품질 저하가 생기는가?
    //     (숙제 3단계에서 직접 관찰)
    //   - similarityThreshold만으로 환각을 100% 막을 수 있는가? Fallback 프롬프트와 어떻게 협업하는가?
    @Bean
    public QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore) {
        // TODO: SearchRequest + QuestionAnswerAdvisor 빌드해 반환 (order=20)
        return null;
    }
}
