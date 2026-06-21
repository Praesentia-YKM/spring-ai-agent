package com.baedal.support.rag;

import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
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

    // [1단계-A] TOP_K — 결정: 4
    //
    // 근거(README §1 설계결정과 동일):
    //   - 정책 문서가 7건뿐이라 청크로 쪼개도 십수 개 수준의 작은 코퍼스다.
    //   - K=1: "환불 + 지연" 같은 복합 질문에서 한쪽 정책만 잡혀 답이 반쪽이 된다.
    //   - K=10: 문서 7건짜리 코퍼스에 10건을 요구하면 임계값 위 무관 청크까지 끌어와
    //           프롬프트(입력 토큰)만 부풀고 노이즈가 섞인다.
    //   - K=4: refund-basic + refund-after-delivered 처럼 한 주제의 2~3개 청크를
    //           동시에 담기에 충분하면서, 7건 코퍼스 대비 과하지 않다.
    private static final int TOP_K = 4;

    // [1단계-B] SIMILARITY_THRESHOLD — 결정: 0.5 (qwen3-embedding:0.6b / COSINE 기준)
    //
    // 근거:
    //   - 도메인 질문("환불 되나요?")은 정책 청크와 0.55~0.7대 점수가 나오고,
    //     도메인 밖 질문("오늘 점심 뭐 먹지?")은 0.5 아래로 떨어지는 경계가 0.5 부근이었다.
    //   - 너무 낮으면(0.3): 도메인 밖 질문에도 무관 정책이 Top-K에 끼어 LLM이 환각.
    //   - 너무 높으면(0.7~0.8): 짧은 구어체 질문("쿠폰 돼요?")의 정답 청크까지 탈락 → Fallback만.
    //   - 임계값 분포 실험은 숙제 3단계에서 0.3/0.5/0.7로 직접 측정한다.
    private static final double SIMILARITY_THRESHOLD = 0.5;

    // [1단계-C] 청크 크기 — 기준값 800 / min 350.
    //
    // @Value로 노출한 이유: 숙제 2단계에서 chunkSize를 100/800/2000으로 바꿔가며
    // 검색 품질을 비교해야 하는데, 매 실험마다 재컴파일하지 않도록 프로퍼티로 뺀다.
    // (ChatMemoryConfig의 baedal.memory.max-messages와 동일한 컨벤션)
    //   예) ./gradlew bootRun --args='--baedal.rag.chunk-size=100 --baedal.rag.min-chunk-size-chars=40'
    @Value("${baedal.rag.chunk-size:800}")
    private int chunkSize;

    @Value("${baedal.rag.min-chunk-size-chars:350}")
    private int minChunkSizeChars;

    /**
     * [1단계-C] TokenTextSplitter Bean.
     * <pre>
     *   chunkSize            — 청크 한 개의 목표 토큰 수
     *   minChunkSizeChars    — 이보다 작으면 앞 청크에 병합
     *   minChunkLengthToEmbed=5  — 이보다 짧으면 임베딩 제외
     *   maxNumChunks=10_000
     *   keepSeparator=true   — 문단 구분자 유지(오버랩/경계 보존에 유리)
     * </pre>
     * 배달 정책 문서는 조항 단위로 이미 잘 끊겨 있어 800/350이면 한 조항이
     * 통째로 한 청크에 담긴다. "블로그/장문 PDF"라면 더 공격적으로 작게 쪼개야 한다.
     */
    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return new TokenTextSplitter(
                chunkSize,
                minChunkSizeChars,
                5,
                10_000,
                true
        );
    }

    /**
     * [1단계-D] QuestionAnswerAdvisor Bean (order=20).
     * <p>
     * 이 Advisor가 매 요청마다 (1) 질문을 임베딩 → (2) similaritySearch(topK, threshold)
     * → (3) 시스템 프롬프트 뒤에 {@code Context:} 블록 주입 → (4) LLM 호출을 자동으로 한다.
     * <p>
     * order(20)인 이유: Memory(10)가 먼저 "아까 그 주문"을 orderId로 복원한 뒤라야
     * RAG가 "복원된 질문"을 임베딩해 올바른 정책을 검색한다(숙제 3단계에서 역전 실험).
     */
    @Bean
    public QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore) {
        SearchRequest searchRequest = SearchRequest.builder()
                .topK(TOP_K)
                .similarityThreshold(SIMILARITY_THRESHOLD)
                .build();

        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(searchRequest)
                .order(20)
                .build();
    }
}
