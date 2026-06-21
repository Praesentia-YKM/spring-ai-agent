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

            // [1단계-E] FaqDocument → Spring AI Document 변환 + VectorStore 적재.
            //   - metadata(faqId/title/category)는 청크들이 상속한다 → 중복 방지 filter,
            //     출처 로깅(title), 카테고리 필터에 쓰인다.
            //   - tokenTextSplitter.apply()로 쪼개야 청크 단위 임베딩이 되어 유사도가 날카로워진다.
            //   - vectorStore.add(chunks)가 내부적으로 EmbeddingModel을 호출해 각 청크를
            //     1024차원 벡터로 만들고 PgVector에 INSERT 한다.
            Document doc = new Document(
                    faq.id(),
                    faq.content(),
                    Map.of(
                            "faqId", faq.id(),
                            "title", faq.title(),
                            "category", faq.category()
                    ));

            List<Document> chunks = tokenTextSplitter.apply(List.of(doc));
            vectorStore.add(chunks);
            loaded++;
            log.info("[KnowledgeLoader] 적재 완료 — id={} / 청크={}개 / 카테고리={}",
                    faq.id(), chunks.size(), faq.category());
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
        // [1단계-F] 중복 적재 방지.
        //   - VectorStore 인터페이스에 "id로 한 건 조회"가 없어, query는 더미("정책")로 두고
        //     similarityThresholdAll()로 임계값을 끈 뒤 filterExpression(faqId)로만 거른다.
        //   - 필요한 건 yes/no 뿐이므로 topK(1)이면 충분하다.
        //   - 한계(README): 내용이 "바뀌어도" faqId가 같으면 스킵한다 → 갱신 미반영.
        //     프로덕션은 content의 SHA-256을 metadata에 넣어 "해시가 다르면 재적재"가 낫다.
        SearchRequest req = SearchRequest.builder()
                .query("정책")
                .topK(1)
                .similarityThresholdAll()
                .filterExpression("faqId == '" + faqId + "'")
                .build();
        return !vectorStore.similaritySearch(req).isEmpty();
    }
}
