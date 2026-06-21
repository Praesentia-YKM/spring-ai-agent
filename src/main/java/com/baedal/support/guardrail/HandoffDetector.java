package com.baedal.support.guardrail;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 5주차 — 상담원 전환(Human Handoff) 트리거 판별기.
 *
 * <h3>왜 LLM 호출 "전"에 판별하는가</h3>
 * <ul>
 *     <li>LLM에게 맡기면 "도움이 되고 싶은" 본성 때문에 "제가 도와드릴게요"로 회피해
 *         감정 고조 고객을 더 화나게 만든다.</li>
 *     <li>전환 문구를 LLM이 매번 새로 지으면 일관성이 깨진다(연결 번호 누락 등).</li>
 *     <li>어차피 사람에게 넘길 건이므로 토큰·지연을 쓸 이유가 없다.</li>
 * </ul>
 * 그래서 Controller에서 LLM 호출 전에 {@link #detect(String)}로 선검사한다.
 *
 * <h3>우선순위: EXPLICIT → LEGAL → ANGER</h3>
 * "이거 너무 화나서 소비자원에 신고할 거예요"는 분노(ANGER)와 법적(LEGAL)이 함께 있다.
 * 이때 ANGER로 처리하면 "죄송합니다" 위주의 공감 문구가 나가지만, 실제로는 <b>법적 사안</b>이라
 * 전문 상담원 연결이 더 급하다. 그래서 LEGAL을 ANGER보다 앞에 둔다. EXPLICIT(명시적 요청)은
 * 고객의 분명한 의사 표현이라 가장 앞이다.
 *
 * <h3>규칙 기반의 한계</h3>
 * "상 담 원"(띄어쓰기), "정말 어이없네"(완곡한 분노), "agent plz"(영문 비정형)처럼
 * 패턴을 살짝 벗어나면 놓친다(FN). 이 한계가 "감정 분류 LLM"을 도입하는 동기다(숙제 3단계).
 */
@Component
public class HandoffDetector {

    /** 전환 연결 번호 — 사유와 무관하게 동일 번호로 안내한다. */
    private static final String AGENT_PHONE = "1600-0987";

    // [3단계-A] 명시적 요청 — 고객이 "사람/상담원"을 분명히 원함.
    //   "상 담 원"처럼 띄어쓰면 일부러 안 잡힌다(규칙 기반 FN 시연용).
    private static final Pattern EXPLICIT = Pattern.compile(
            "상담원|상담사|사람\\s*(이랑|하고|과)?\\s*(얘기|통화|연결|바꿔)|" +
            "직원\\s*(연결|바꿔)|담당자\\s*연결|사람\\s*불러");

    // [3단계-B] 법적/민원 — 분노보다 우선 처리해야 하는 사안.
    private static final Pattern LEGAL = Pattern.compile(
            "소송|변호사|소비자원|고소|신고|법적|민원|공정위|손해배상");

    // [3단계-C] 감정 고조 — 강한 분노 표현.
    //   "불편"(완곡)·"어이없"(우회)은 일부러 제외해 규칙 기반의 누락(FN)을 드러낸다.
    private static final Pattern ANGER = Pattern.compile(
            "너무\\s*화|화가\\s*나|화나는|화났|짜증|미치겠|답답해\\s*죽|답답해죽|열\\s*받|빡쳐|환장");

    /**
     * 입력을 판별해 전환 여부·사유·문구를 돌려준다. (Advisor와 분리해 단위 테스트 가능)
     */
    public HandoffDecision detect(String input) {
        if (input == null || input.isBlank()) {
            return HandoffDecision.none();
        }
        // 우선순위대로 — 먼저 매치되는 사유로 확정한다.
        if (EXPLICIT.matcher(input).find()) {
            return HandoffDecision.of(Reason.EXPLICIT_REQUEST);
        }
        if (LEGAL.matcher(input).find()) {
            return HandoffDecision.of(Reason.LEGAL_ISSUE);
        }
        if (ANGER.matcher(input).find()) {
            return HandoffDecision.of(Reason.HIGH_EMOTION);
        }
        return HandoffDecision.none();
    }

    /** 전환 사유 — 사유별로 응답 톤이 달라야 한다(사과 → 공감 → 행동). */
    public enum Reason {
        NONE(null),
        EXPLICIT_REQUEST(
                "네, 바로 상담원에게 연결해 드릴게요. 잠시만 기다려 주세요. (상담원 연결: " + AGENT_PHONE + ")"),
        // 감정 고조: "도와드릴게요"만 반복하면 불에 기름 → 사과를 먼저 넣는다.
        HIGH_EMOTION(
                "많이 불편하셨을 것 같아 정말 죄송합니다. 상담원이 직접 도와드릴 수 있도록 연결해 드릴게요. " +
                "(상담원 연결: " + AGENT_PHONE + ")"),
        LEGAL_ISSUE(
                "법적·민원 관련 사안은 전문 상담원이 도와드려야 합니다. 상담원 연결을 진행할게요. " +
                "(상담원 연결: " + AGENT_PHONE + ")");

        private final String message;

        Reason(String message) {
            this.message = message;
        }
    }

    /**
     * 판별 결과 — 전환 여부 / 사유 / 고객 응답 문구.
     */
    public record HandoffDecision(boolean handoff, Reason reason, String message) {
        public static HandoffDecision none() {
            return new HandoffDecision(false, Reason.NONE, null);
        }
        public static HandoffDecision of(Reason reason) {
            return new HandoffDecision(true, reason, reason.message);
        }
    }
}
