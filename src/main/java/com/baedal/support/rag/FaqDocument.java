package com.baedal.support.rag;

/**
 * 4주차 — FAQ 시드 문서 도메인.
 * <p>
 * VectorStore에 적재되기 전의 "원본 지식 조각"을 표현한다.
 * Spring AI의 {@code org.springframework.ai.document.Document}로 변환하기 전 단계다.
 *
 * <h3>필드 의미</h3>
 * <ul>
 *     <li>{@code id}: 시드 파일의 고유 식별자 (예: {@code refund-policy-01}).
 *         {@link KnowledgeLoader}가 중복 적재를 막기 위해 metadata로 같이 저장한다.</li>
 *     <li>{@code title}: 원문 문서의 섹션 제목. 검색 결과를 사람이 확인할 때 유용.</li>
 *     <li>{@code category}: {@code refund}, {@code delivery-delay}, {@code coupon} 등
 *         정책/FAQ의 카테고리. Advisor의 필터링 파라미터로 활용 가능.</li>
 *     <li>{@code content}: 실제 임베딩 대상이 되는 본문.
 *         길면 {@code TokenTextSplitter}가 청크로 쪼갠다.</li>
 * </ul>
 */
public record FaqDocument(
        String id,
        String title,
        String category,
        String content
) {}
