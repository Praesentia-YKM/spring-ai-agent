# Round 3 — AI 코드 리뷰: 직접 만든 메모리의 프로덕션 결함

## 1. 프롬프트
AI(Claude)에게 의도적으로 **순진한** 요청을 던졌다:

> "Spring AI 1.0으로 배달 챗봇에 대화 메모리 기능을 붙여줘. 세션별로 대화가 유지되어야 해."

## 2. AI 생성 원본 코드 (베이스라인)
세션별 유지라는 요구만 보고 흔히 나오는 형태 — `Map`으로 직접 관리:

```java
@Component
public class ChatMemoryService {

    // 세션별 대화 기록
    private final Map<String, List<Message>> store = new HashMap<>();

    public String chat(ChatClient.Builder builder, String sessionId, String userText) {
        List<Message> history = store.computeIfAbsent(sessionId, k -> new ArrayList<>());

        history.add(new UserMessage(userText));

        String answer = builder.build()
                .prompt()
                .messages(history)          // 전체 히스토리를 매번 통째로 전달
                .call()
                .content();

        history.add(new AssistantMessage(answer));
        return answer;
    }
}
```

겉보기엔 "세션별로 대화가 유지"된다. 그러나 프로덕션에 올리면 4가지가 동시에 터진다.

## 3. 프로덕션 결함 3+1건과 개선 (이번 라운드에서 배운 방식)

### 결함 ① 메모리 크기 제한 없음 → 입력 토큰 선형 폭증
`history`에 무한히 append. `MessageWindow`가 없어 100턴이면 입력이 100배가 된다.

- **실측 근거**: 2단계 실험 C(`MAX_VALUE`)에서 입력 토큰이 턴마다 `1322 → 1430 → 1559 → … → 4299`로 선형 증가([raw §2단계](raw/scenarios.md)). 윈도우가 없으면 곧 컨텍스트 윈도우를 넘는다.
- **개선**: `MessageWindowChatMemory.maxMessages(20)` 으로 최근 N개만 유지.
  ```java
  MessageWindowChatMemory.builder().chatMemoryRepository(repo).maxMessages(20).build();
  ```
  → 실험 A(20)는 turn 10에서 입력 2407로 평탄화 시작. ([ChatMemoryConfig.java](../../src/main/java/com/baedal/support/ChatMemoryConfig.java))

### 결함 ② 동시성 — `HashMap`은 thread-unsafe
`HashMap` + `List` 를 여러 요청 스레드가 동시에 건드린다. 리사이즈 중 무한루프(JDK8 이전)·데이터 유실·`ConcurrentModificationException` 위험.

- **개선**: Spring AI의 `InMemoryChatMemoryRepository`는 내부적으로 `ConcurrentHashMap`을 쓴다. 직접 Map을 들지 말고 `ChatMemoryRepository`에 위임.
  ```java
  @Bean @Profile("!jdbc")
  ChatMemoryRepository chatMemoryRepository() { return new InMemoryChatMemoryRepository(); }
  ```

### 결함 ③ 재시작 소실 + 멀티 인스턴스 미고려
로컬 `Map`이라 (a) 서버 재시작 시 전 상담 이력 증발, (b) 로드밸런서 뒤 2번째 인스턴스는 기억이 없음.

- **실측 근거**: 3단계에서 InMemory/h2:mem은 재시작 후 `/session/ids` 가 비었고, h2:file은 대화가 살아남았다([raw §3단계](raw/scenarios.md)).
- **개선**: 저장 레이어만 `JdbcChatMemoryRepository`로 교체(합성 구조라 ④·⑤ 무수정). 멀티 인스턴스는 공유 DB(PostgreSQL)로 해결.
  ```groovy
  implementation 'org.springframework.ai:spring-ai-starter-model-chat-memory-repository-jdbc'
  ```

### 결함 ④ (보너스) 프라이버시 — 평문 저장 + TTL 없음
주소·전화번호가 대화에 섞여 평문으로 DB에 쌓인다. 보존 기간 정책도 없다.

- **개선 방향**: 저장 전 민감정보 마스킹(Round 5 Guardrail) + 90일 TTL 배치 삭제 + 컬럼 암호화. **단, 윈도우+JDBC는 세션당 최근 N개만 남기므로 "전체 감사 로그"가 아니다** — 감사가 필요하면 append-only 히스토리 로그를 별도로 둔다([ADR-005](adr/ADR-005-memory-is-not-audit-log.md)).

## 4. 한 줄 요약
AI는 "세션별 유지"라는 **기능**은 즉시 만들지만, **경계**(크기/동시성/영속성/프라이버시)는 빠뜨린다. Round 3의 3레이어(`Repository`/`Window`/`Advisor`)는 정확히 그 경계를 끼워 넣는 장치다.

| 결함 | AI 코드 | Round 3 개선 | 실측 근거 |
|------|---------|--------------|-----------|
| ① 크기 무제한 | `history.add()` 무한 | `MessageWindowChatMemory(20)` | 2단계 C 선형증가 |
| ② thread-unsafe | `HashMap` | `InMemoryChatMemoryRepository`(ConcurrentHashMap) | — |
| ③ 재시작 소실 | 로컬 `Map` | `JdbcChatMemoryRepository` | 3단계 file 생존 |
| ④ 프라이버시 | 평문·무기한 | 마스킹+TTL+암호화 (Round 5) | — |
