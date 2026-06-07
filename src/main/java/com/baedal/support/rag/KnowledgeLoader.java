package com.baedal.support.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 4주차 — 정책/FAQ 문서를 VectorStore에 적재하는 ApplicationRunner.
 *
 * <h3>동작 순서</h3>
 * <ol>
 *     <li>{@code classpath:knowledge/*.md} 파일을 모두 읽는다.</li>
 *     <li>파일명에서 id/category를 뽑아 {@link FaqDocument}로 변환한다.</li>
 *     <li>각 문서를 Spring AI {@link Document}로 변환하며 metadata를 심는다.</li>
 *     <li>{@link TokenTextSplitter}로 긴 문서를 청크로 쪼갠다.</li>
 *     <li>이미 적재된 id가 있으면 스킵, 없으면 VectorStore에 저장한다.</li>
 * </ol>
 *
 * <h3>왜 ApplicationRunner인가</h3>
 * <ul>
 *     <li>{@code @PostConstruct}는 Bean 초기화 단계라서 VectorStore의 DataSource가
 *         완전히 준비되지 않았을 수 있다.</li>
 *     <li>ApplicationRunner는 <b>ApplicationContext 기동이 완료된 후</b> 실행되므로
 *         VectorStore의 schema 초기화 이후에 안전하게 동작한다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeLoader implements ApplicationRunner {

    private final VectorStore vectorStore;
    private final TokenTextSplitter tokenTextSplitter;

    private static final String KNOWLEDGE_LOCATION = "classpath:/knowledge/*.md";

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources(KNOWLEDGE_LOCATION);

        if (resources.length == 0) {
            log.warn("[KnowledgeLoader] knowledge 리소스가 없습니다 — RAG 시드 스킵");
            return;
        }

        int loaded = 0;
        int skipped = 0;

        for (Resource resource : resources) {
            FaqDocument faq = parse(resource);

            if (alreadyLoaded(faq.id())) {
                skipped++;
                log.debug("[KnowledgeLoader] 이미 적재됨 — id={} ({})", faq.id(), faq.title());
                continue;
            }

            // TODO [1단계-E] FaqDocument → Spring AI Document 변환 + VectorStore 적재.
            //
            // 요구사항:
            //   1) Document doc = new Document(
            //          faq.id(),
            //          faq.content(),
            //          Map.of(
            //              "faqId",    faq.id(),
            //              "title",    faq.title(),
            //              "category", faq.category()
            //          ));
            //   2) List<Document> chunks = tokenTextSplitter.apply(List.of(doc));
            //   3) vectorStore.add(chunks);
            //   4) loaded++;
            //   5) log.info("[KnowledgeLoader] 적재 완료 — id={} / 청크={}개 / 카테고리={}",
            //                faq.id(), chunks.size(), faq.category());
            //
            // 왜 metadata에 faqId/title/category를 넣는가:
            //   - 중복 적재 방지: 아래 alreadyLoaded() 가 filterExpression으로 faqId를 검사한다.
            //   - 검색 후 출처 표기: metadata.title로 "어떤 정책 문서를 인용했는지" 로그에 찍을 수 있다.
            //   - 카테고리 필터: 필요시 "환불 카테고리 안에서만 검색" 같은 필터를 쓸 수 있다.
            //
            // 설계 결정 질문 (README):
            //   - 왜 tokenTextSplitter.apply()로 쪼개는가? 원본 Document 한 개로 넣으면 뭐가 달라지는가?
            //   - vectorStore.add(chunks)는 내부적으로 무엇을 하는가? (힌트: EmbeddingModel 호출)
        }

        log.info("[KnowledgeLoader] RAG 시드 완료 — 신규 {}건 / 스킵 {}건 / 총 {}건",
                loaded, skipped, resources.length);
    }

    /**
     * 파일명 컨벤션에서 id/category를 추출한다.
     * <p>
     * 컨벤션: {@code {category}__{id}.md}
     * <p>
     * 예: {@code refund__refund-basic.md} → category=refund, id=refund-basic
     * <p>
     * 이 메서드는 교육 범위가 아니므로 완성 상태로 제공된다.
     */
    private FaqDocument parse(Resource resource) throws Exception {
        String filename = resource.getFilename();  // refund__refund-basic.md
        if (filename == null) {
            throw new IllegalStateException("리소스 파일명을 읽을 수 없습니다: " + resource);
        }
        String base = filename.replaceFirst("\\.md$", "");

        String category;
        String id;
        int sep = base.indexOf("__");
        if (sep > 0) {
            category = base.substring(0, sep);
            id = base.substring(sep + 2);
        } else {
            category = "general";
            id = base;
        }

        String body;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            body = reader.lines().collect(Collectors.joining("\n"));
        }

        // 첫 번째 '# ' 라인을 제목으로 사용
        String title = body.lines()
                .filter(l -> l.startsWith("# "))
                .findFirst()
                .map(l -> l.substring(2).trim())
                .orElse(id);

        return new FaqDocument(id, title, category, body);
    }

    /**
     * 같은 faqId로 이미 VectorStore에 저장된 문서가 있는지 확인한다.
     */
    private boolean alreadyLoaded(String faqId) {
        // TODO [1단계-F] 중복 적재 방지 로직을 구현하라.
        //
        // 요구사항:
        //   SearchRequest req = SearchRequest.builder()
        //           .query("정책")                            // 아무 쿼리나 OK — filter로만 걸러짐
        //           .topK(1)
        //           .similarityThresholdAll()                 // 유사도 임계값 없음
        //           .filterExpression("faqId == '" + faqId + "'")
        //           .build();
        //   return !vectorStore.similaritySearch(req).isEmpty();
        //
        // 왜 이 방법을 쓰는가:
        //   - Spring AI의 VectorStore 인터페이스에는 "id로 한 건 조회"가 없다.
        //   - 필요한 건 "이미 있는지의 yes/no" 뿐이므로 similaritySearch + filter로 충분하다.
        //
        // 설계 결정 질문 (README):
        //   - 프로덕션에서는 이 방식의 어떤 한계가 있는가?
        //     (힌트: 문서 "내용이 바뀌었을 때"는 감지 못 한다. 해시 기반 전략과 비교하라.)
        //
        // 힌트: 지금은 일단 false를 반환해 빌드가 되게만 해두고, 위 코드를 채워라.
        //       false로 두면 재기동마다 동일 문서가 또 쌓이는 것을 관찰하게 된다(실패 관찰).
        return false;
    }
}
