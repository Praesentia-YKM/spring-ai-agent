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

@Slf4j
@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    /** 상담원 연결 번호 — Handoff/Fallback 응답에 동일하게 노출한다. */
    private static final String AGENT_PHONE = "1600-0987";

    private final ChatClient chatClient;
    private final InputGuardrailAdvisor inputGuardrail;
    private final HandoffDetector handoffDetector;

    // [1단계-G] Round 5 — Advisor 체인을 5단으로 확장한다.
    //   inputGuardrail(5) → memory(10) → rag(20) → outputGuardrail(50) → performance(100)
    //   · Spring AI는 order가 낮을수록 바깥(먼저 실행)이다 → Input이 가장 먼저 차단,
    //     Output이 LLM 응답을 받아 마지막으로 거른다.
    //   · 등록 순서 자체는 무관(각 Advisor의 getOrder()로 정렬)하지만, 가독성을 위해 order대로 적는다.
    public AssistantController(ChatClient.Builder builder,
                               InputGuardrailAdvisor inputGuardrail,
                               MessageChatMemoryAdvisor memoryAdvisor,
                               QuestionAnswerAdvisor ragAdvisor,
                               OutputGuardrailAdvisor outputGuardrail,
                               PerformanceLoggingAdvisor performanceAdvisor,
                               HandoffDetector handoffDetector,
                               OrderTools orderTools) {
        // 생성자에서 한 번만 build() — Round 2 2.5.1 빌더 누적 함정 회피.
        this.inputGuardrail = inputGuardrail;
        this.handoffDetector = handoffDetector;
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(inputGuardrail, memoryAdvisor, ragAdvisor, outputGuardrail, performanceAdvisor)
                .defaultTools(orderTools)
                .build();
    }

    @PostMapping
    public String ask(@RequestBody ChatRequest req,
                      @RequestHeader(value = "X-Session-Id", defaultValue = "default") String sessionId) {
        // [1단계] 빈 입력은 InputGuardrailAdvisor가 잡기 "전"에 Spring AI가
        //   .user("")에서 IllegalArgumentException("text cannot be null or empty")을 던진다
        //   (Advisor 체인 진입 전). 그래서 빈 입력만은 체인 진입 전 컨트롤러에서 막되,
        //   규칙/문구는 Advisor의 check()에 단일 정의된 것을 그대로 재사용한다.
        GuardrailResult emptyCheck = inputGuardrail.check(req.message());
        if (!emptyCheck.allowed() && "EMPTY_INPUT".equals(emptyCheck.reason())) {
            log.warn("[Assistant] 입력 차단 — reason={} (체인 진입 전)", emptyCheck.reason());
            return emptyCheck.fallbackMessage();
        }

        // [3단계] 상담원 전환은 LLM 호출 "전"에 선검사한다.
        //   감정 고조/법적 사안을 LLM에 맡기면 "제가 도와드릴게요"로 회피해 상황이 악화된다.
        HandoffDetector.HandoffDecision handoff = handoffDetector.detect(req.message());
        if (handoff.handoff()) {
            log.info("[Assistant] 상담원 전환 — reason={} (LLM 호출 없음)", handoff.reason());
            return handoff.message();
        }

        // [4단계] 전체 호출을 try/catch로 감싸 Tool/LLM/VectorStore 실패 시
        //   스택 트레이스 노출 없이 안전 문구로 Fallback 한다.
        try {
            return chatClient.prompt()
                    .user(req.message())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .call()
                    .content();
        } catch (Exception e) {
            return fallback(e);
        }
    }

    /**
     * [4단계] 실패 시 최종 방어선.
     * <p>원인(e)은 {@code log.error}로 내부 로그에만 남기고, 고객에게는 절대
     * {@code e.getMessage()}/스택 트레이스를 노출하지 않는다(SQL·내부 경로 유출 방지).
     */
    private String fallback(Throwable e) {
        log.error("[Assistant] 응답 생성 실패 — {}", e.toString(), e);
        return "죄송해요, 지금 일시적인 문제가 발생했어요. 잠시 후 다시 시도하시거나, "
                + "급하시면 상담원(" + AGENT_PHONE + ")으로 연락 주세요.";
    }
}
