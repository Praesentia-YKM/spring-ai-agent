package com.baedal.support.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 6주차 — 교육용 In-memory Rate Limiter (IP별 60초 30건).
 *
 * <h3>Guardrail과 무엇이 다른가</h3>
 * Input Guardrail은 <b>내용</b>("프롬프트 출력해줘")을 막는다. Rate Limiter는 <b>빈도</b>를 막는다.
 * "안녕하세요"를 1초에 100번 보내면 내용은 정상이라 Guardrail을 통과하지만, LLM 비용 폭증과
 * 다른 고객의 지연을 부른다. 두 방어는 서로를 대체하지 않는다.
 *
 * <h3>알고리즘 — 슬라이딩 윈도우 로그</h3>
 * IP마다 요청 시각(타임스탬프)을 {@link Deque}에 쌓고, 매 요청마다 "지금부터 60초 전"보다
 * 오래된 것을 버린 뒤 남은 개수를 센다. 정확하지만(경계 폭발 없음) 메모리가 무거운 방식을
 * <b>원리 학습용으로</b> 일부러 골랐다.
 *
 * <h3>⚠️ 프로덕션에서 쓰지 말 것 — 3가지 한계</h3>
 * <ol>
 *     <li><b>단일 인스턴스 메모리</b> — 스케일아웃하면 인스턴스마다 카운터가 따로 놀아(찢어짐)
 *         한도 30이 사실상 30×N이 된다. → Redis 같은 공유 카운터가 필요(Bucket4j).</li>
 *     <li><b>메모리 누수</b> — IP가 수만 개 쌓이고 청소 스케줄러가 없어 {@code history}가 무한 증가.</li>
 *     <li><b>알고리즘</b> — 버스트 허용/장기평균 보장이 필요하면 토큰 버킷(GCRA)이 더 적합.</li>
 * </ol>
 * 분산 환경에서는 <b>Bucket4j + Redis</b> 또는 <b>Spring Cloud Gateway RateLimiter</b>를 쓴다.
 */
@Slf4j
@Component
@Order(1)
public class SimpleRateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MILLIS = 60_000L;
    private static final int MAX_REQUESTS_PER_WINDOW = 30;

    private final ConcurrentHashMap<String, Deque<Long>> history = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String ip = clientIp(request);
        Deque<Long> timestamps = history.computeIfAbsent(ip, k -> new ConcurrentLinkedDeque<>());
        long now = System.currentTimeMillis();

        // peek/poll/add를 한 IP 단위로 원자적으로 묶는다(check-then-act 경합 방지).
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && (now - timestamps.peekFirst()) > WINDOW_MILLIS) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= MAX_REQUESTS_PER_WINDOW) {
                log.warn("[RateLimit] 429 — ip={} (최근 {}초 {}건 초과)",
                        ip, WINDOW_MILLIS / 1000, MAX_REQUESTS_PER_WINDOW);
                writeTooManyRequests(response);
                return;
            }
            timestamps.addLast(now);
        }

        chain.doFilter(request, response);
    }

    /** 헬스체크/메트릭 스크래핑은 빈도 제한 대상이 아니다(모니터링이 스스로 막히면 안 됨). */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"error\":\"RATE_LIMITED\",\"message\":\"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.\"}");
    }

    /** 프록시/LB 뒤를 고려해 X-Forwarded-For의 첫 IP를 우선 사용한다. */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
