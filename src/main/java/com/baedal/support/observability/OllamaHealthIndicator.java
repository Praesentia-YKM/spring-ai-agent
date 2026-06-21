package com.baedal.support.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 6주차 — Ollama(외부 LLM 의존성) 헬스 체크.
 *
 * <h3>왜 직접 만들어야 하나</h3>
 * Spring Boot Actuator는 {@code DataSource} 같은 일부 의존성만 자동으로 건강 체크한다.
 * Ollama는 Spring 입장에서 "그냥 외부 HTTP 서비스"라 자동 감지되지 않는다. 그래서 직접
 * {@link HealthIndicator}를 붙여, Ollama가 죽으면 {@code /actuator/health}의 {@code ollama}
 * 컴포넌트가 DOWN으로 바뀌게 한다. (application.yml에서 readiness 그룹에 포함시켜,
 * 로드밸런서/쿠버네티스가 죽은 인스턴스로 트래픽을 보내지 않도록 한다 — 장애 전파 차단.)
 *
 * <h3>왜 가벼운 핑인가</h3>
 * 헬스 체크는 "의존성이 살아있는지"만 확인한다. {@code /api/tags}(모델 목록)는 가볍지만,
 * {@code POST /api/generate}로 실제 추론을 시키면 헬스 체크가 매번 부하 테스트가 되어
 * <b>헬스 체크 자체가 장애 증폭기</b>가 된다. 또한 행(hang) 방지를 위해 짧은 타임아웃을 둔다.
 */
@Component("ollama")
public class OllamaHealthIndicator implements HealthIndicator {

    private static final int TIMEOUT_MILLIS = 2_000;

    private final RestClient client;
    private final String baseUrl;

    public OllamaHealthIndicator(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl) {
        this.baseUrl = baseUrl;
        // 행 방지: 헬스 체크가 느려지면 그 자체가 장애가 된다 → 연결/읽기 타임아웃을 짧게.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MILLIS);
        factory.setReadTimeout(TIMEOUT_MILLIS);
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @Override
    public Health health() {
        try {
            String body = client.get().uri("/api/tags").retrieve().body(String.class);
            return Health.up()
                    .withDetail("baseUrl", baseUrl)
                    .withDetail("responseLength", body == null ? 0 : body.length())
                    .build();
        } catch (Exception e) {
            // 예외 메시지만 detail로 남기고 스택은 노출하지 않는다.
            return Health.down()
                    .withDetail("baseUrl", baseUrl)
                    .withDetail("error", e.getClass().getSimpleName() + ": " + e.getMessage())
                    .build();
        }
    }
}
