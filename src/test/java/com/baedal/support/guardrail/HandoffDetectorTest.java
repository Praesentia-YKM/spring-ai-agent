package com.baedal.support.guardrail;

import com.baedal.support.guardrail.HandoffDetector.HandoffDecision;
import com.baedal.support.guardrail.HandoffDetector.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [3단계 증거] Handoff 트리거 — 우선순위(EXPLICIT → LEGAL → ANGER)와 규칙 기반 한계(FN).
 */
class HandoffDetectorTest {

    private final HandoffDetector detector = new HandoffDetector();

    @Test
    void 명시적_요청은_EXPLICIT() {
        HandoffDecision d = detector.detect("상담원이랑 직접 얘기하고 싶어요");
        assertTrue(d.handoff());
        assertEquals(Reason.EXPLICIT_REQUEST, d.reason());
        assertTrue(d.message().contains("1600-0987"));
    }

    @Test
    @DisplayName("분노+법적이 함께면 LEGAL이 우선한다")
    void 분노와_법적이_겹치면_LEGAL_우선() {
        HandoffDecision d = detector.detect("이거 너무 화나서 소비자원에 신고할 거예요");
        assertEquals(Reason.LEGAL_ISSUE, d.reason());  // ANGER 아님
    }

    @Test
    void 순수_분노는_HIGH_EMOTION() {
        HandoffDecision d = detector.detect("나 너무 화나는데 답답해 죽겠네");
        assertEquals(Reason.HIGH_EMOTION, d.reason());
        assertTrue(d.message().startsWith("많이 불편하셨을"));  // 사과 먼저
    }

    @Test
    void 일반_상담은_전환하지_않는다() {
        assertFalse(detector.detect("비 오는 날 배달 늦으면 보상 받나요?").handoff());
    }

    @Test
    @DisplayName("규칙 기반 한계 — 띄어쓰기/완곡 표현은 놓친다(FN)")
    void 우회_표현은_탐지하지_못한다() {
        assertFalse(detector.detect("상 담 원 연결해 주세요").handoff());     // 띄어쓰기 우회
        assertFalse(detector.detect("진짜 너무너무 불편했습니다").handoff());  // 완곡한 분노
    }
}
