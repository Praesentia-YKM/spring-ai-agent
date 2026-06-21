package com.baedal.support.guardrail;

/**
 * 5주차 — Input Guardrail 검사 1건의 결과를 표현하는 값 객체.
 *
 * <p>{@link InputGuardrailAdvisor#check(String)}가 입력 1건을 검사한 뒤
 * "통과/차단 + 차단 사유 + 고객에게 보여줄 문구"를 한 덩어리로 묶어 돌려준다.
 *
 * <h3>왜 boolean이 아니라 객체인가</h3>
 * <ul>
 *     <li>차단 시 <b>사유(reason)</b>를 로그에 남겨야 어떤 패턴이 막았는지 관찰할 수 있다
 *         (숙제 1단계: {@code PROMPT_INJECTION / EMPTY_INPUT / INPUT_TOO_LONG}).</li>
 *     <li>차단 사유마다 <b>고객 친화적 문구(fallbackMessage)</b>가 달라야 한다.
 *         "너무 길다"와 "공격 시도"에 같은 안내를 내보내면 고객 경험이 나빠진다.</li>
 * </ul>
 */
public record GuardrailResult(boolean allowed, String reason, String fallbackMessage) {

    /** 통과. fallbackMessage는 쓰이지 않으므로 null. */
    public static GuardrailResult allow() {
        return new GuardrailResult(true, "OK", null);
    }

    /** 차단. reason은 로그/메트릭용, fallbackMessage는 고객 응답용. */
    public static GuardrailResult block(String reason, String fallbackMessage) {
        return new GuardrailResult(false, reason, fallbackMessage);
    }
}
