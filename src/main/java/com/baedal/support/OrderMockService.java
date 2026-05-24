package com.baedal.support;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class OrderMockService {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    @PostConstruct
    void seed() {
        LocalDateTime now = LocalDateTime.now();

        // 2024-1234: DELIVERING — 라이브 데모 기본 시나리오 (라이더 위치 조회)
        put(new Order(
                "2024-1234", "교촌치킨 역삼점",
                List.of(new OrderItem("허니콤보", 1, 23000),
                        new OrderItem("콜라 1.25L", 1, 3000)),
                26000, now.minusMinutes(35), now.plusMinutes(15),
                "서울 강남구 역삼동 123-4", "역삼역 사거리",
                OrderStatus.DELIVERING));

        // 2024-1235: CREATED — Quest 시나리오 3 (방금 시킨 거 취소)
        put(new Order(
                "2024-1235", "맘스터치 강남점",
                List.of(new OrderItem("싸이버거 세트", 2, 7900)),
                15800, now.minusMinutes(2), now.plusMinutes(40),
                "서울 강남구 논현동 11-22", null,
                OrderStatus.CREATED));

        // 2024-1236: DELIVERED — Quest 시나리오 4 (이미 배달 완료, 취소 불가)
        put(new Order(
                "2024-1236", "스타벅스 강남R점",
                List.of(new OrderItem("아메리카노 T", 2, 4500),
                        new OrderItem("치즈케이크", 1, 6500)),
                15500, now.minusMinutes(80), now.minusMinutes(20),
                "서울 강남구 테헤란로 555", null,
                OrderStatus.DELIVERED));

        // 2024-1237: COOKING — 강의 데모 (조리 시작 — 취소 불가 다른 경로)
        put(new Order(
                "2024-1237", "BHC 강남직영점",
                List.of(new OrderItem("뿌링클", 1, 22000)),
                22000, now.minusMinutes(10), now.plusMinutes(45),
                "서울 강남구 삼성동 99-1", null,
                OrderStatus.COOKING));

        // 2024-1238: 사전 CANCELED — 멱등 테스트용 (canceledReason 채워둠)
        Order canceled = new Order(
                "2024-1238", "BBQ 역삼점",
                List.of(new OrderItem("황금올리브", 1, 21000)),
                21000, now.minusMinutes(45), now.minusMinutes(30),
                "서울 강남구 역삼동 555-1", null,
                OrderStatus.ACCEPTED);
        canceled.cancel("고객 요청", now.minusMinutes(40));
        put(canceled);

        // 2024-1239: ACCEPTED — Quest 2단계 멱등성 실험용 (취소 → ALREADY_CANCELED)
        put(new Order(
                "2024-1239", "도미노피자 강남점",
                List.of(new OrderItem("페퍼로니피자 L", 1, 28900)),
                28900, now.minusMinutes(5), now.plusMinutes(35),
                "서울 강남구 신사동 77-3", null,
                OrderStatus.ACCEPTED));

        log.info("OrderMockService seeded — {}건", orders.size());
    }

    private void put(Order order) {
        orders.put(order.getOrderId(), order);
    }

    public Optional<Order> findById(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }
}
