package com.baedal.support;

import java.util.List;

public record SupportResponse(
        String summary,
        Category category,
        Urgency urgency,
        String nextAction,
        List<String> neededInfo,
        EstimatedResolution estimatedResolution
) {
    public enum Category { ORDER, DELIVERY, REFUND, PAYMENT, ETC }
    public enum Urgency  { LOW, NORMAL, HIGH, CRITICAL }

    public enum EstimatedResolution {
        IMMEDIATE,
        WITHIN_30MIN,
        WITHIN_1DAY,
        EXTENDED
    }
}
