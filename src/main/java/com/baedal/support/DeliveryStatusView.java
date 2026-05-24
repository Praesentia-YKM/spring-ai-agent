package com.baedal.support;

import java.time.LocalDateTime;

public record DeliveryStatusView(
        String orderId,
        String status,
        LocalDateTime estimatedDeliveryAt,
        String riderLocation
) {}
