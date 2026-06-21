package com.baedal.support.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 6주차 — 에이전트 고유 지표를 모으는 단일 진입점(퍼사드).
 *
 * <h3>왜 퍼사드인가</h3>
 * Advisor·Tool·Controller가 <b>각자 {@link MeterRegistry}를 직접 건드리면</b> 메트릭 이름·태그가
 * 제각각이 되어 카디널리티가 터지거나 시계열이 파편화된다. 이름/태그 규약을 여기 한 곳에 모아두면
 * "어떤 지표가 있는지"가 이 파일 하나로 설명된다.
 *
 * <h3>지표 목록 (7종)</h3>
 * <pre>
 *   baedal.agent.request.total              요청 총량                          (Counter)
 *   baedal.agent.fallback                   안전 Fallback 횟수(200 OK로 숨은 실패) (Counter)
 *   baedal.agent.llm.latency                LLM 왕복 지연(Tool/RAG 제외)        (Timer)
 *   baedal.agent.guardrail.block{kind,reason}  Guardrail 차단/치환             (Counter)
 *   baedal.agent.handoff{reason}            상담원 전환                         (Counter)
 *   baedal.agent.tool.invoke{tool,outcome}  Tool 호출 성공/실패                (Counter)
 *   baedal.agent.tokens{type}               토큰 사용량(비용 대리지표)          (Counter)
 * </pre>
 *
 * <h3>태그 설계 — 카디널리티 폭발 금지</h3>
 * 모든 태그 값은 "열거형에 가깝게(enum-like)" 유지한다. {@code reason="사용자 입력 원문"},
 * {@code tag("ip", ...)}, {@code tag("orderId", ...)} 같은 무한 값은 절대 넣지 않는다 —
 * 조합마다 별도 시계열이 생겨 Prometheus 메모리를 터뜨린다. 그런 고카디널리티 정보는
 * 메트릭이 아니라 로그/추적에 둔다.
 */
@Component
public class AgentMetrics {

    private final MeterRegistry registry;

    /** 요청 총량 — 모든 트래픽의 분모. */
    public final Counter requestTotal;
    /** 안전 Fallback 횟수 — 5xx가 아니라 200 OK로 "숨은" 실패를 드러내는 별도 카운터. */
    public final Counter fallbackTotal;
    /** LLM 왕복 지연 — 체인 최내곽(order=100)에서 측정해 Tool/RAG 지연과 분리한다. */
    public final Timer llmLatency;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.requestTotal = Counter.builder("baedal.agent.request.total")
                .description("에이전트가 받은 전체 요청 수")
                .register(registry);
        this.fallbackTotal = Counter.builder("baedal.agent.fallback")
                .description("LLM/Tool/VectorStore 실패로 안전 Fallback이 나간 횟수(200 OK로 숨은 실패)")
                .register(registry);
        this.llmLatency = Timer.builder("baedal.agent.llm.latency")
                .description("LLM 왕복 지연(Tool/RAG 제외)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    /** LLM 왕복 시간을 기록한다(ms). */
    public void recordLlmLatency(long elapsedMillis) {
        llmLatency.record(elapsedMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Guardrail 차단/치환 1건.
     * @param kind   "input" | "output" (열거형)
     * @param reason PROMPT_INJECTION / INPUT_TOO_LONG / EMPTY_INPUT / PROMPT_LEAK / SENSITIVE_MASKED / EMPTY_RESPONSE
     */
    public void guardrailBlock(String kind, String reason) {
        Counter.builder("baedal.agent.guardrail.block")
                .tag("kind", kind)
                .tag("reason", reason)
                .description("Guardrail 차단/치환 건수")
                .register(registry)
                .increment();
    }

    /**
     * 상담원 전환 1건.
     * @param reason EXPLICIT_REQUEST / HIGH_EMOTION / LEGAL_ISSUE (HandoffDetector.Reason)
     */
    public void handoff(String reason) {
        Counter.builder("baedal.agent.handoff")
                .tag("reason", reason)
                .description("상담원 전환 건수")
                .register(registry)
                .increment();
    }

    /**
     * Tool 호출 1건.
     * @param tool    getOrderDetail / getDeliveryStatus / cancelOrder (유한)
     * @param outcome success / not_found / error (열거형)
     */
    public void toolInvoke(String tool, String outcome) {
        Counter.builder("baedal.agent.tool.invoke")
                .tag("tool", tool)
                .tag("outcome", outcome)
                .description("Tool 호출 건수(성공/실패)")
                .register(registry)
                .increment();
    }

    /**
     * 토큰 사용량 누적.
     * @param type  prompt | completion | total (열거형)
     * @param count 해당 호출의 토큰 수
     */
    public void tokens(String type, long count) {
        Counter.builder("baedal.agent.tokens")
                .tag("type", type)
                .baseUnit("tokens")
                .description("LLM 토큰 사용량(비용 대리지표)")
                .register(registry)
                .increment(count);
    }
}
