package com.baedal.support;

import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Round 3 — 대화 메모리 3레이어 조립.
 *
 * <pre>
 *   ⑤ MessageChatMemoryAdvisor  (흐름에 끼워넣는 어댑터)
 *        └─ 품음 → ④ MessageWindowChatMemory  (최근 N개 정책)
 *                      └─ 품음 → ③ ChatMemoryRepository  (저장소)
 * </pre>
 *
 * 합성(has-a)으로 포갠 구조라, 저장 기술(③)만 InMemory ↔ JDBC로 갈아끼우면
 * ④·⑤는 코드 한 줄 안 바꿔도 동작한다.
 */
@Configuration
public class ChatMemoryConfig {

    /**
     * 슬라이딩 윈도우 크기. 기본 20.
     * 2단계 실험(20 / 2 / MAX_VALUE)을 재컴파일 없이 돌리기 위해 프로퍼티로 노출한다.
     * 예) ./gradlew bootRun --args='--baedal.memory.max-messages=2'
     */
    @Value("${baedal.memory.max-messages:20}")
    private int maxMessages;

    /**
     * ③ 저장소 — InMemory (ConcurrentHashMap 기반).
     * jdbc 프로필에서는 자동 구성된 JdbcChatMemoryRepository가 주입되도록 이 Bean을 제외한다.
     */
    @Bean
    @Profile("!jdbc")
    public ChatMemoryRepository chatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    /**
     * ④ 정책 — 최근 maxMessages개만 유지하는 슬라이딩 윈도우.
     * 초과 시 가장 오래된 메시지부터 잘라낸다(SystemMessage는 보존).
     */
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(maxMessages)
                .build();
    }

    /**
     * ⑤ 어댑터 — ChatClient 호출 흐름의 before/after에 Memory를 연결.
     * order(10): PerformanceLoggingAdvisor(order 100)보다 먼저 실행되어
     * 과거 메시지가 프롬프트에 합쳐진 '뒤'의 입력 토큰이 로그에 잡힌다.
     */
    @Bean
    public MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory)
                .order(10)
                .build();
    }
}
