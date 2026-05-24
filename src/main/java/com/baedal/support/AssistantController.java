package com.baedal.support;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final ChatClient chatClient;

    public AssistantController(ChatClient.Builder builder,
                               PerformanceLoggingAdvisor performanceAdvisor,
                               OrderTools orderTools) {
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(performanceAdvisor)
                .defaultTools(orderTools)
                .build();
    }

    @PostMapping
    public String ask(@RequestBody ChatRequest req) {
        return chatClient.prompt()
                .user(req.message())
                .call()
                .content();
    }
}
