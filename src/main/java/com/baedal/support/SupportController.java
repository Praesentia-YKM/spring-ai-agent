package com.baedal.support;

import com.baedal.support.guardrail.GuardrailResult;
import com.baedal.support.guardrail.HandoffDetector;
import com.baedal.support.guardrail.InputGuardrailAdvisor;
import com.baedal.support.guardrail.OutputGuardrailAdvisor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/support")
public class SupportController {

    private static final String AGENT_PHONE = "1600-0987";

    private final ChatClient chatClient;
    private final InputGuardrailAdvisor inputGuardrail;
    private final HandoffDetector handoffDetector;

    // [1단계-H] AssistantController와 동일한 5단 체인 — 두 엔드포인트가 같은 안전장치를 공유한다.
    public SupportController(ChatClient.Builder builder,
                             InputGuardrailAdvisor inputGuardrail,
                             MessageChatMemoryAdvisor memoryAdvisor,
                             QuestionAnswerAdvisor ragAdvisor,
                             OutputGuardrailAdvisor outputGuardrail,
                             PerformanceLoggingAdvisor performanceAdvisor,
                             HandoffDetector handoffDetector,
                             OrderTools orderTools) {
        this.inputGuardrail = inputGuardrail;
        this.handoffDetector = handoffDetector;
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(inputGuardrail, memoryAdvisor, ragAdvisor, outputGuardrail, performanceAdvisor)
                .defaultTools(orderTools)
                .build();
    }

    @PostMapping
    public SupportResponse triage(@RequestBody ChatRequest req,
                                  @RequestHeader(value = "X-Session-Id", defaultValue = "default") String sessionId) {
        // [1단계] 빈 입력은 체인 진입 전 Spring AI가 예외를 던지므로, 여기서 EMPTY_INPUT으로 막는다.
        GuardrailResult emptyCheck = inputGuardrail.check(req.message());
        if (!emptyCheck.allowed() && "EMPTY_INPUT".equals(emptyCheck.reason())) {
            log.warn("[Support] 입력 차단 — reason={} (체인 진입 전)", emptyCheck.reason());
            return new SupportResponse(
                    emptyCheck.fallbackMessage(),
                    SupportResponse.Category.ETC,
                    SupportResponse.Urgency.LOW,
                    "문의 내용 입력 요청",
                    List.of(),
                    SupportResponse.EstimatedResolution.IMMEDIATE);
        }

        // [3단계] Handoff 선검사 — Structured Output 엔드포인트에서는 SupportResponse를 수동 조립한다.
        HandoffDetector.HandoffDecision handoff = handoffDetector.detect(req.message());
        if (handoff.handoff()) {
            log.info("[Support] 상담원 전환 — reason={} (LLM 호출 없음)", handoff.reason());
            return handoffResponse(handoff.message());
        }

        try {
            return chatClient.prompt()
                    .user(req.message())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .call()
                    .entity(SupportResponse.class);
        } catch (Exception e) {
            return fallback(e);
        }
    }

    /** [3단계] 전환 건은 Category=ETC, Urgency=HIGH, action="상담원 연결 진행"으로 스키마에 맞춰 조립. */
    private SupportResponse handoffResponse(String message) {
        return new SupportResponse(
                message,
                SupportResponse.Category.ETC,
                SupportResponse.Urgency.HIGH,
                "상담원 연결 진행",
                List.of(),
                SupportResponse.EstimatedResolution.EXTENDED
        );
    }

    /** [4단계] 실패 시 안전 Fallback — 스택 트레이스는 내부 로그에만. */
    private SupportResponse fallback(Throwable e) {
        log.error("[Support] 응답 생성 실패 — {}", e.toString(), e);
        return new SupportResponse(
                "죄송해요, 지금 일시적인 문제가 발생했어요. 잠시 후 다시 시도하시거나, "
                        + "급하시면 상담원(" + AGENT_PHONE + ")으로 연락 주세요.",
                SupportResponse.Category.ETC,
                SupportResponse.Urgency.HIGH,
                "상담원 연결 또는 재시도",
                List.of(),
                SupportResponse.EstimatedResolution.EXTENDED
        );
    }
}
