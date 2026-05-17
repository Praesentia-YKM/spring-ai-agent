package com.baedal.support;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/prompt-lab")
public class PromptLabController {

    private final ChatClient.Builder builder;
    private final PerformanceLoggingAdvisor performanceAdvisor;

    @PostMapping
    public PromptLabResult experiment(@RequestBody PromptLabRequest req) {
        var client = builder
                .defaultSystem(req.systemPrompt())
                .defaultAdvisors(performanceAdvisor)
                .build();

        var results = new ArrayList<SupportResponse>();
        for (int i = 0; i < req.repeat(); i++) {
            var response = client.prompt()
                    .user(req.message())
                    .call()
                    .entity(SupportResponse.class);
            results.add(response);
        }

        return PromptLabResult.from(results);
    }

    public record PromptLabRequest(
            String systemPrompt,
            String message,
            int repeat
    ) {}

    public record PromptLabResult(
            int totalRuns,
            Map<String, Long> categoryCounts,
            Map<String, Long> urgencyCounts,
            double categoryConsistency,
            List<SupportResponse> rawResults
    ) {
        public static PromptLabResult from(List<SupportResponse> results) {
            var catCounts = results.stream()
                    .collect(Collectors.groupingBy(
                            r -> r.category().name(), Collectors.counting()));
            var urgCounts = results.stream()
                    .collect(Collectors.groupingBy(
                            r -> r.urgency().name(), Collectors.counting()));
            long maxCat = catCounts.values().stream()
                    .mapToLong(Long::longValue).max().orElse(0);

            return new PromptLabResult(
                    results.size(), catCounts, urgCounts,
                    results.isEmpty() ? 0 : (double) maxCat / results.size(),
                    results
            );
        }
    }
}
