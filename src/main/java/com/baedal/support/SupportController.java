package com.baedal.support;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/support")
public class SupportController {

    private final ChatClient chatClient;

    // [1단계-H — round4-04 레퍼런스 완성 ③] SupportController에도 동일한 체인이 적용돼 있다.
    //
    // 참고: .defaultAdvisors(...)는 다음과 같이 구성된다:
    //   .defaultAdvisors(memoryAdvisor, ragAdvisor, performanceAdvisor)
    // AssistantController와 완전히 동일한 순서여야 두 엔드포인트가
    // 같은 정책 지식·대화 맥락을 공유해 일관된 상담이 된다.
    public SupportController(ChatClient.Builder builder,
                             MessageChatMemoryAdvisor memoryAdvisor,
                             QuestionAnswerAdvisor ragAdvisor,
                             PerformanceLoggingAdvisor performanceAdvisor,
                             OrderTools orderTools) {
        // 생성자에서 한 번만 build() — 빌더 누적 함정 회피.
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                // memory(10) → rag(20) → performance(100)
                .defaultAdvisors(memoryAdvisor, ragAdvisor, performanceAdvisor)
                .defaultTools(orderTools)
                .build();
    }

    @PostMapping
    public SupportResponse triage(@RequestBody ChatRequest req,
                                  @RequestHeader(value = "X-Session-Id", defaultValue = "default") String sessionId) {
        return chatClient.prompt()
                .user(req.message())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .entity(SupportResponse.class);
    }
}
