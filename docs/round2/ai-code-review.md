# Quest 4 — AI 코드 리뷰

> Round 2 강의 4단계 + Round 1 피드백의 "AI 코드 리뷰 가이드"에 따라, AI가 일반적으로 생성하는 `@Tool cancelOrder` 코드의 단골 결함 8가지를 본 라운드에서 배운 도구로 짚어 본 학습자 코드와 대조한다.

## 1. AI에 줄 프롬프트

본 가이드의 베이스라인 AI 코드는 다음 프롬프트로 받는다. 학습자는 본인 IDE/Claude/ChatGPT에서 직접 실행 후 결과를 README에 첨부.

```
Spring AI 1.0으로 배달 주문 취소 Tool을 만들어줘.
@Tool 어노테이션을 써야 해. 주문번호와 취소 사유를 받고,
취소가 성공하면 true, 실패하면 false를 반환해.
```

프롬프트는 의도적으로 단순하게 두었다. *"AI가 무엇을 빠뜨리는가"*를 측정하기 위해서다.

## 2. 일반적 AI 생성 코드 (베이스라인)

```java
@Component
public class OrderCancelTool {

    @Autowired
    private OrderRepository orderRepository;

    @Tool("Cancel an order by orderId")
    public boolean cancelOrder(String orderId, String reason) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus("CANCELED");
        order.setCanceledReason(reason);
        orderRepository.save(order);
        return true;
    }
}
```

컴파일이 되고 단순 시나리오에서는 동작하지만, 프로덕션에 올리면 안 되는 결함이 다음과 같이 8개 있다.

## 3. 결함 8가지와 본 학습자 코드 대응

| # | AI 코드 결함 | Round 2에서 배운 도구 | 본 학습자 코드의 적용 위치 |
|---|-------------|-----------------------|---------------------------|
| 1 | **멱등성 없음** — 두 번째 호출 시 status 덮어쓰임, canceledReason 변경 | `Outcome.ALREADY_CANCELED` 분기 | `OrderTools.cancelOrder` L40~43 |
| 2 | **예외 직접 throw** — `orElseThrow()`가 LLM에 raw exception 전달, fallback 불가 | `Outcome.NOT_FOUND` 반환 | `OrderTools.cancelOrder` L31~34 |
| 3 | **반환 정보 부족** — boolean 하나로는 LLM이 *왜 실패했는지* 알 수 없음 | `CancelOrderResult` record + `Outcome` enum 4분기 | `CancelOrderResult.java` |
| 4 | **권한 검증 없음** — 누구나 임의 `orderId`로 호출 가능 | (Round 5 Guardrail에서 다룰 영역) | TODO: `@PreAuthorize` 또는 인증 컨텍스트 검증 |
| 5 | **description 부실** — 영어 한 줄 `"Cancel an order"`. 언제 호출할지·입력 형식·실패 케이스 모두 누락 | description 4요소(무엇/언제/입력/실패) + 한국어 | `OrderTools.cancelOrder` description 9줄 (L38~45) |
| 6 | **로깅 없음** — 감사(Audit) 불가, 누가 무엇을 시도했는지 추적 불가 | `@Slf4j` + `log.info("[Tool] cancelOrder(orderId=..., reason=...)")` | `OrderTools.cancelOrder` L49 |
| 7 | **Outcome 구분 없음** — `boolean` 반환은 *"왜"*가 없다. NOT_FOUND / NOT_CANCELABLE / CANCELED 구분 불가 | `Outcome` enum 4분기 + 자연어 message 동봉 | `CancelOrderResult.Outcome` enum |
| 8 | **`ChatClient.Builder` 매 요청 `.build()`** (이 코드엔 없지만 Controller에 흔히 등장) — Tool 누적 등록 사고 | 생성자에서 한 번만 build + ChatClient 재사용 | `AssistantController` / `SupportController` 생성자 |

## 4. 본 학습자 OrderTools.cancelOrder

```java
@Tool(description = """
        주어진 주문번호의 주문을 취소한다.
        취소 가능 조건: 주문 상태가 CREATED 또는 ACCEPTED인 경우에만 가능.
        조리가 이미 시작된(COOKING 이후) 주문은 자동 취소할 수 없다 (NOT_CANCELABLE).
        이미 취소된 주문을 다시 취소 요청하면 에러가 아닌 ALREADY_CANCELED 결과를 돌려준다 (멱등).
        존재하지 않는 주문번호면 NOT_FOUND를 반환한다.
        결과는 항상 CancelOrderResult 객체로 반환되며, outcome 필드에서 성공/실패 사유를 확인할 수 있다.
        """)
public CancelOrderResult cancelOrder(
        @ToolParam(description = "취소할 주문번호. 예: 2024-1234") String orderId,
        @ToolParam(description = "고객이 말한 취소 사유. 예: '집앞에 사람이 없어요'") String reason) {
    log.info("[Tool] cancelOrder(orderId={}, reason={})", orderId, reason);  // 결함 6 대응

    Order order = orderService.findById(orderId).orElse(null);
    if (order == null) {
        return new CancelOrderResult(orderId, Outcome.NOT_FOUND,                  // 결함 2 대응
                "해당 주문번호를 찾을 수 없습니다.");
    }
    if (order.getStatus() == OrderStatus.CANCELED) {
        return new CancelOrderResult(orderId, Outcome.ALREADY_CANCELED,            // 결함 1 대응
                "해당 주문은 이미 취소된 상태입니다. (취소 사유: " + order.getCanceledReason() + ")");
    }
    if (!order.isCancelable()) {
        return new CancelOrderResult(orderId, Outcome.NOT_CANCELABLE,              // 결함 7 대응
                "조리가 이미 시작되어(" + order.getStatus() + ") 자동 취소가 불가합니다.");
    }
    order.cancel(reason, LocalDateTime.now());
    return new CancelOrderResult(orderId, Outcome.CANCELED,                        // 결함 3 대응
            "주문이 취소되었습니다. 결제 취소는 카드사에 따라 최대 7영업일이 소요될 수 있습니다.");
}
```

## 5. 비교 매트릭스

| 영역 | AI 코드 (베이스라인) | 본 학습자 코드 |
|------|---------------------|----------------|
| 반환 타입 | `boolean` | `CancelOrderResult` + `Outcome` enum 4분기 |
| 예외 처리 | `orElseThrow()` raw | `NOT_FOUND` outcome 반환 |
| 멱등성 | 없음 — 덮어쓰임 가능 | `ALREADY_CANCELED` 분기 |
| description | 영어 한 줄 | 한국어 4요소 |
| 로깅 | 없음 | `@Slf4j` + `log.info` |
| DTO 분리 | `Order` 엔티티 직접 노출 | View DTO (`CancelOrderResult`) |
| 권한 검증 | 없음 | (Round 5 예정) |
| ChatClient 빌더 | 매 요청 `.build()` 가능 | 생성자에서 한 번만 |

## 6. 같은 결함 패턴이 다음 라운드에서 또 등장하는 자리

- Round 4 RAG — `QuestionAnswerAdvisor`의 응답을 받을 때 결함 3(엔티티 직접 반환)과 같은 함정. View DTO 분리 학습 그대로 적용.
- Round 5 Guardrail — 결함 4(권한 검증 없음)가 본격적으로 다뤄진다. Tool 호출 전 사용자 컨텍스트 검증 필수.
- Round 6 운영화 — 결함 6(로깅 없음)의 확장. 감사 로그 + 메트릭 + Tracing.

## 7. 평가 채점 시 부분 점수와 만점이 갈리는 지점

부분 점수 답:
> "AI가 만든 코드에는 에러 핸들링이 없습니다. 빌더 재사용도 안 합니다."

만점 답 — 8가지 결함을 본 학습자 코드 라인 번호까지 대조:
> "단골 결함 8개 중 7개를 식별(#4 권한은 Round 5에서). 각각을 본 학습자 코드 라인에 대응해서 *AI가 어디까지 빠뜨리는지* 와 *본 라운드 학습으로 어디까지 잡혔는지* 양방향을 명시. #1 멱등성은 [`failure-observations` 관찰 9](failure-observations/round2-failure-observations.md) 의 실험 결과로 보강."
