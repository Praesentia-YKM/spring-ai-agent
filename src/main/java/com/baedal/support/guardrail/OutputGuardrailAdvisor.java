package com.baedal.support.guardrail;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 5주차 — Output Guardrail (order=50).
 *
 * <h3>역할</h3>
 * LLM이 토해낸 "날것" 응답을 고객에게 내보내기 <b>직전</b>에 검사·가공한다.
 * Input에서 막지 못한 것, LLM이 확률적으로 흘린 것을 마지막으로 거른다(다층 방어).
 *
 * <h3>왜 Input만으로는 부족한가</h3>
 * 공격적 입력이 아니어도("사장님 번호 맞나요?") Tool/RAG Context에 섞여 들어온 개인정보를
 * LLM이 그대로 복창할 수 있다. Input은 "들어오는 것", Output은 "나가는 것"을 본다 — 책임이 다르다.
 *
 * <h3>order=50 — Memory/RAG(주입) 뒤, Performance(로깅) 안쪽</h3>
 * <pre>
 *   Input(5) → Memory(10) → RAG(20) → OutputGuardrail(50) → Performance(100) → LLM
 * </pre>
 * Output은 {@link CallAdvisorChain#nextCall}로 LLM 응답을 먼저 받아야 검사할 수 있으므로
 * 응답 가공형(post-process) Advisor다.
 *
 * <h3>3가지 방어</h3>
 * <ol>
 *     <li>빈 응답 → {@code EMPTY_FALLBACK} (토큰 한도 초과/모델 오작동 방어)</li>
 *     <li>시스템 프롬프트 섹션명 유출 → 통째로 {@code LEAK_FALLBACK} 치환</li>
 *     <li>민감 정보(전화/이메일/주소) → 값만 마스킹(맥락 보존)</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutputGuardrailAdvisor implements CallAdvisor {

    private final SensitiveDataMasker masker;

    // [2단계-D] 시스템 프롬프트 유출 마커 — BaedalPrompt의 섹션 헤더들.
    //   LLM이 응답에 이 섹션명을 그대로 토해내면 내부 지침이 새어나간 증거다.
    private static final List<String> LEAK_MARKERS = List.of(
            "[역할]", "[규칙]", "[금지]", "[안전 규칙]",
            "[대화 맥락 사용 규칙]", "[도구 사용 규칙]", "[정책 인용 규칙]", "[응답 포맷]"
    );

    private static final String LEAK_FALLBACK =
            "죄송해요, 그 내용은 안내해 드릴 수 없어요. 저는 주문·배달·취소·환불·쿠폰 상담을 도와드립니다. " +
            "어떤 도움이 필요하신지 말씀해 주세요.";

    private static final String EMPTY_FALLBACK =
            "죄송해요, 답변을 만들지 못했어요. 질문을 조금만 더 구체적으로 다시 보내주시겠어요?";

    @Override
    public String getName() {
        return "OutputGuardrailAdvisor";
    }

    @Override
    public int getOrder() {
        return 50;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);  // 먼저 LLM까지 다 실행
        String original = extractContent(response);

        // [2단계-E①] 빈 응답 방어
        if (original.isBlank()) {
            log.warn("[OutputGuardrail] 빈 응답 감지 — reason=EMPTY_RESPONSE");
            return replace(response, request, EMPTY_FALLBACK);
        }

        // [2단계-E②] 시스템 프롬프트 유출 → 통째 치환 (마스킹보다 우선: 더 심각)
        for (String marker : LEAK_MARKERS) {
            if (original.contains(marker)) {
                log.warn("[OutputGuardrail] 응답 치환 — reason=PROMPT_LEAK | marker={}", marker);
                return replace(response, request, LEAK_FALLBACK);
            }
        }

        // [2단계-E③] 민감 정보 → 값만 마스킹 (맥락 유지)
        if (masker.containsSensitive(original)) {
            String masked = masker.mask(original);
            // 학습용 한정: 원본은 평문이라 DEBUG로만 대조한다(운영에서는 평문 로그 금지 — AI 코드리뷰 결함 참고).
            log.debug("[OutputGuardrail] 마스킹 전: {}", original);
            log.info("[OutputGuardrail] 민감 정보 마스킹 적용");
            log.info("[OutputGuardrail] 응답 치환 — reason=SENSITIVE_MASKED");
            return replace(response, request, masked);
        }

        return response;
    }

    /** 응답 본문(AssistantMessage 텍스트)을 안전하게 꺼낸다. */
    private String extractContent(ChatClientResponse response) {
        ChatResponse cr = response.chatResponse();
        if (cr == null || cr.getResult() == null || cr.getResult().getOutput() == null) {
            return "";
        }
        String text = cr.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    /** 본문만 새 텍스트로 바꾼 ChatClientResponse를 재조립한다(metadata/context는 유지). */
    private ChatClientResponse replace(ChatClientResponse response, ChatClientRequest request, String newText) {
        AssistantMessage message = new AssistantMessage(newText);
        Generation generation = new Generation(message);
        ChatResponse.Builder builder = ChatResponse.builder().generations(List.of(generation));

        ChatResponse original = response.chatResponse();
        if (original != null && original.getMetadata() != null) {
            // 토큰/모델 metadata는 보존 → Performance 로깅이 실제 사용량을 계속 집계할 수 있다.
            builder.metadata(original.getMetadata());
        }
        return ChatClientResponse.builder()
                .chatResponse(builder.build())
                .context(request.context())
                .build();
    }
}
