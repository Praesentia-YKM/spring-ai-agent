package com.baedal.support;

public record CancelOrderResult(
        String orderId,
        Outcome outcome,
        String message
) {
    public enum Outcome {
        /** 이번 호출에서 취소됨 */
        CANCELED,
        /** 이미 취소되어 있었음 (멱등 — 에러 아님) */
        ALREADY_CANCELED,
        /** 조리 시작 이후 등 자동 취소 불가 */
        NOT_CANCELABLE,
        /** 주문번호 없음 */
        NOT_FOUND
    }
}
