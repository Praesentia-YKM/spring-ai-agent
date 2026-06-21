package com.baedal.support;

import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 5주차 [4단계] — Tool 예외 전파 정책.
 *
 * <h3>왜 필요한가</h3>
 * Spring AI 1.0의 기본 {@link ToolExecutionExceptionProcessor}는 {@code alwaysThrow=false}라,
 * {@code @Tool} 메서드가 던진 예외를 <b>삼켜서 그 메시지를 LLM에 도구 결과로 돌려준다</b>.
 * 그러면 LLM이 그 오류를 받아 "알아서" 응답을 이어가므로:
 * <ul>
 *     <li>Controller의 {@code try/catch}(최종 방어선)가 작동하지 않는다.</li>
 *     <li>LLM이 도구 호출 흔적을 텍스트로 흘리는 등 응답 품질이 떨어진다(실측 관찰).</li>
 * </ul>
 *
 * <h3>결정: alwaysThrow=true</h3>
 * Tool 실패를 Controller까지 전파시켜, 스택 트레이스 노출 없이 일관된 안전 Fallback 문구로
 * 응답하게 한다(발제 §5.3 "RuntimeException은 전체 호출을 중단시킨다"와 동일한 모델).
 * <p>주의: 이는 "예외(Exception)" 전파에만 관여한다. 정상적인 "주문 없음"은 여전히 Tool이
 * {@code null}을 반환해 LLM이 자연스럽게 "주문을 찾을 수 없다"로 설명한다(§5.3 권장).
 */
@Configuration
public class ToolConfig {

    @Bean
    public ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
        return DefaultToolExecutionExceptionProcessor.builder()
                .alwaysThrow(true)
                .build();
    }
}
