package com.baedal.support;

import com.baedal.support.observability.AgentMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTools {

    private final OrderMockService orderService;
    private final AgentMetrics metrics;

    @Tool(description = """
            주어진 주문번호의 상세 정보를 조회한다.
            메뉴, 수량, 금액, 주문 상태, 예상 배달 완료 시각을 반환한다.
            사용자가 "어떤 메뉴 시켰는지", "얼마 결제했는지", "주문 내역" 등을 물을 때 호출한다.
            주문번호는 "YYYY-XXXX" 형식이며 (예: 2024-1234),
            존재하지 않는 주문번호면 null을 반환한다.
            """)
    public OrderDetailView getOrderDetail(
            @ToolParam(description = "조회할 주문번호. 예: 2024-1234") String orderId) {
        log.info("[Tool] getOrderDetail(orderId={})", orderId);
        try {
            OrderDetailView view = orderService.findById(orderId).map(this::toDetailView).orElse(null);
            metrics.toolInvoke("getOrderDetail", view == null ? "not_found" : "success");
            return view;
        } catch (Exception e) {
            // 설계 원칙: Tool은 예외를 던지지 않고 null/결과 객체를 돌려준다(LLM이 자연스럽게 대응).
            metrics.toolInvoke("getOrderDetail", "error");
            log.error("[Tool] getOrderDetail 실패 — {}", e.toString());
            return null;
        }
    }

    @Tool(description = """
            주어진 주문번호의 현재 배달 상태와 라이더 위치를 조회한다.
            사용자가 "지금 어디쯤?", "라이더 위치", "도착 시각" 등 배달 진행 상태를 물을 때 호출한다.
            배달 중(DELIVERING)인 주문에 대해서만 riderLocation이 채워지며,
            아직 배달이 시작되지 않았거나 이미 배달 완료된 주문은 상태만 반환된다.
            존재하지 않는 주문번호면 null을 반환한다.
            """)
    public DeliveryStatusView getDeliveryStatus(
            @ToolParam(description = "배달 상태를 조회할 주문번호. 예: 2024-1234") String orderId) {
        log.info("[Tool] getDeliveryStatus(orderId={})", orderId);
        try {
            DeliveryStatusView view = orderService.findById(orderId).map(this::toDeliveryView).orElse(null);
            metrics.toolInvoke("getDeliveryStatus", view == null ? "not_found" : "success");
            return view;
        } catch (Exception e) {
            metrics.toolInvoke("getDeliveryStatus", "error");
            log.error("[Tool] getDeliveryStatus 실패 — {}", e.toString());
            return null;
        }
    }

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
        log.info("[Tool] cancelOrder(orderId={}, reason={})", orderId, reason);
        try {
            CancelOrderResult result = doCancelOrder(orderId, reason);
            metrics.toolInvoke("cancelOrder", outcomeTag(result.outcome()));
            return result;
        } catch (Exception e) {
            // 설계 원칙: Tool은 예외를 던지지 않는다 → 결과 객체로 안전하게 돌려준다.
            metrics.toolInvoke("cancelOrder", "error");
            log.error("[Tool] cancelOrder 실패 — {}", e.toString());
            return new CancelOrderResult(orderId, CancelOrderResult.Outcome.NOT_FOUND,
                    "주문 취소 처리 중 문제가 발생했어요. 잠시 후 다시 시도하시거나 상담원에게 문의해 주세요.");
        }
    }

    /** cancelOrder의 실제 처리 로직(예외/메트릭 래핑과 분리). */
    private CancelOrderResult doCancelOrder(String orderId, String reason) {
        Order order = orderService.findById(orderId).orElse(null);
        if (order == null) {
            return new CancelOrderResult(orderId, CancelOrderResult.Outcome.NOT_FOUND,
                    "해당 주문번호를 찾을 수 없습니다.");
        }

        // 멱등성: 이미 취소된 주문은 에러 없이 ALREADY_CANCELED를 돌려준다.
        // (이 분기를 제거하면 NOT_CANCELABLE 메시지로 빠져 "조리 진행 중" 같은 거짓 안내 발생 —
        //  failure-observations 관찰 8 참조)
        if (order.getStatus() == OrderStatus.CANCELED) {
            return new CancelOrderResult(orderId, CancelOrderResult.Outcome.ALREADY_CANCELED,
                    "해당 주문은 이미 취소된 상태입니다. (취소 사유: " + order.getCanceledReason() + ")");
        }

        if (!order.isCancelable()) {
            return new CancelOrderResult(orderId, CancelOrderResult.Outcome.NOT_CANCELABLE,
                    "조리가 이미 시작되어(" + order.getStatus() + ") 자동 취소가 불가합니다. 상담원 연결이 필요합니다.");
        }

        order.cancel(reason, LocalDateTime.now());
        return new CancelOrderResult(orderId, CancelOrderResult.Outcome.CANCELED,
                "주문이 취소되었습니다. 결제 취소는 카드사에 따라 최대 7영업일이 소요될 수 있습니다.");
    }

    /** Tool 호출 결과를 저카디널리티 outcome 태그로 정규화한다. */
    private static String outcomeTag(CancelOrderResult.Outcome outcome) {
        return switch (outcome) {
            case CANCELED, ALREADY_CANCELED -> "success";
            case NOT_FOUND -> "not_found";
            case NOT_CANCELABLE -> "not_cancelable";
        };
    }

    private OrderDetailView toDetailView(Order o) {
        return new OrderDetailView(
                o.getOrderId(),
                o.getStoreName(),
                o.getItems().stream()
                        .map(it -> new OrderDetailView.Line(it.menuName(), it.quantity(), it.unitPrice()))
                        .toList(),
                o.getTotalAmount(),
                o.getStatus().name(),
                o.getOrderedAt(),
                o.getEstimatedDeliveryAt()
        );
    }

    private DeliveryStatusView toDeliveryView(Order o) {
        return new DeliveryStatusView(
                o.getOrderId(),
                o.getStatus().name(),
                o.getEstimatedDeliveryAt(),
                // 배달 중 상태에서만 라이더 위치 노출
                o.getStatus() == OrderStatus.DELIVERING ? o.getRiderLocation() : null
        );
    }
}
