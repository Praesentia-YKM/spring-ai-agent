package com.baedal.support;

import com.baedal.support.observability.AgentMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PerformanceLoggingAdvisor implements CallAdvisor {

    private final AgentMetrics metrics;

    @Override
    public String getName() {
        return "PerformanceLoggingAdvisor";
    }

    @Override
    public int getOrder() {
        // 체인 바깥쪽에서 LLM 왕복 시간을 측정하기 위해 큰 값을 준다.
        return 100;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.currentTimeMillis();
        ChatClientResponse response = chain.nextCall(request);
        long elapsed = System.currentTimeMillis() - start;
        metrics.recordLlmLatency(elapsed);   // 6주차: LLM 왕복 지연(Timer)

        var chatResponse = response.chatResponse();
        if (chatResponse != null
                && chatResponse.getMetadata() != null
                && chatResponse.getMetadata().getUsage() != null) {
            var usage = chatResponse.getMetadata().getUsage();
            // 6주차: 토큰 사용량(비용 대리지표) — null이 아닐 때만 누적한다.
            Integer promptTokens = usage.getPromptTokens();
            Integer completionTokens = usage.getCompletionTokens();
            Integer totalTokens = usage.getTotalTokens();
            if (promptTokens != null) {
                metrics.tokens("prompt", promptTokens);
            }
            if (completionTokens != null) {
                metrics.tokens("completion", completionTokens);
            }
            if (totalTokens != null) {
                metrics.tokens("total", totalTokens);
            }
            log.info("LLM 호출 완료 — {}ms | 입력 토큰: {} | 출력 토큰: {} | 총 토큰: {}",
                    elapsed,
                    promptTokens,
                    completionTokens,
                    totalTokens);
        } else {
            log.info("LLM 호출 완료 — {}ms (metadata 없음)", elapsed);
        }

        return response;
    }
}
