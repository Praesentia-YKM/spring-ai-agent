package com.baedal.support;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final ChatClient chatClient;

    // TODO [1단계-G] Advisor 체인에 ragAdvisor를 추가하라.
    //
    // 아래 .defaultAdvisors(...)를 다음과 같이 바꾼다:
    //   .defaultAdvisors(memoryAdvisor, ragAdvisor, performanceAdvisor)
    //                    order=10       order=20    order=100
    // 순서 주의: memory가 먼저 "아까 그 주문"의 orderId를 복원해야
    //           RAG가 "그 주문의 환불 정책"을 검색할 수 있다.
    // (ragAdvisor는 이미 생성자 파라미터로 주입받는다 — 체인에 끼우기만 하면 된다.)
    public AssistantController(ChatClient.Builder builder,
                               MessageChatMemoryAdvisor memoryAdvisor,
                               QuestionAnswerAdvisor ragAdvisor,
                               PerformanceLoggingAdvisor performanceAdvisor,
                               OrderTools orderTools) {
        // 생성자에서 한 번만 build() — Round 2 2.5.1 빌더 누적 함정 회피.
        // memoryAdvisor를 먼저 등록: order(10) < order(100) 이라 과거 주입이 먼저 일어나고,
        // 그 '뒤'의 입력 토큰을 PerformanceLoggingAdvisor가 측정한다.
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                // TODO: ragAdvisor를 memoryAdvisor 다음, performanceAdvisor 앞에 추가하라.
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
