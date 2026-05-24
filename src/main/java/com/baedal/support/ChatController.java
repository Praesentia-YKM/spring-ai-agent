package com.baedal.support;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatClient.Builder chatClientBuilder;
    private final OrderTools orderTools;

    @PostMapping
    public String chat(@RequestBody ChatRequest request) {
        return chatClientBuilder.build()
                .prompt()
                .advisors(new SimpleLoggerAdvisor())
                .tools(orderTools)
                .user(request.message())
                .call()
                .content();
    }
}
