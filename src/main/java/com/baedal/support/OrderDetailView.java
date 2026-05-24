package com.baedal.support;

import java.time.LocalDateTime;
import java.util.List;

public record OrderDetailView(
        String orderId,
        String storeName,
        List<Line> items,
        int totalAmount,
        String status,
        LocalDateTime orderedAt,
        LocalDateTime estimatedDeliveryAt
) {
    public record Line(String menuName, int quantity, int unitPrice) {}
}
