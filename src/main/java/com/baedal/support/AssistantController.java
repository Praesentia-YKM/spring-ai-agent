package com.baedal.support;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final ChatClient chatClient;

    public AssistantController(ChatClient.Builder builder,
                               MessageChatMemoryAdvisor memoryAdvisor,
                               PerformanceLoggingAdvisor performanceAdvisor,
                               OrderTools orderTools) {
        // 생성자에서 한 번만 build() — Round 2 2.5.1 빌더 누적 함정 회피.
        // memoryAdvisor를 먼저 등록: order(10) < order(100) 이라 과거 주입이 먼저 일어나고,
        // 그 '뒤'의 입력 토큰을 PerformanceLoggingAdvisor가 측정한다.
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(memoryAdvisor, performanceAdvisor)
                .defaultTools(orderTools)
                .build();
    }

    @PostMapping
    public String ask(@RequestBody ChatRequest req,
                      @RequestHeader(value = "X-Session-Id", defaultValue = "default") String sessionId) {
        // 이 호출에 한해 '어느 세션의 Memory를 쓸지' 지정. 이 한 줄이 고객별 분리의 핵심.
        return chatClient.prompt()
                .user(req.message())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();
    }
}
