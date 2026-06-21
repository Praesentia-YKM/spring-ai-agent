package com.baedal.support.guardrail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [2단계 증거] 마스킹 정확도 — 정상 마스킹 + 과잉 마스킹(FP) + 누락(FN)을 못 박는다.
 * <p>Ollama/PgVector 없이 {@code ./gradlew test}로 재현 가능한 실측 근거.
 */
class SensitiveDataMaskerTest {

    private final SensitiveDataMasker masker = new SensitiveDataMasker();

    @Nested
    @DisplayName("정상 마스킹")
    class HappyPath {

        @Test
        void 전화번호_가운데를_가린다() {
            assertEquals("010-****-5678", masker.maskPhone("010-1234-5678"));
        }

        @Test
        void 문장_속_전화번호도_맥락을_유지하며_가린다() {
            assertEquals("연락처 010-****-5678 로 안내드릴게요",
                    masker.maskPhone("연락처 010-1234-5678 로 안내드릴게요"));
        }

        @Test
        void 이메일은_첫_글자만_남긴다() {
            assertEquals("l***@woowahan.com", masker.maskEmail("len@woowahan.com"));
            assertEquals("a***@b.co", masker.maskEmail("a@b.co"));
        }

        @Test
        void 행정동_주소는_통째로_가린다() {
            assertEquals("[주소 비공개]", masker.maskAddress("서울시 강남구 역삼동 123-45"));
            assertEquals("[주소 비공개]", masker.maskAddress("서울 강남구 역삼동 12"));
        }

        @Test
        void 세_종류를_한_번에_마스킹한다() {
            String in = "내 번호 010-1111-2222 / 메일 a@b.co / 서울시 강남구 역삼동 12 저장돼 있어요?";
            String out = masker.mask(in);
            assertTrue(out.contains("010-****-2222"), out);
            assertTrue(out.contains("a***@b.co"), out);
            assertTrue(out.contains("[주소 비공개]"), out);
        }
    }

    @Nested
    @DisplayName("과잉 마스킹 방지 (FP) — 합법 숫자는 건드리지 않는다")
    class FalsePositiveGuard {

        @Test
        void 주문번호_2024_1234는_전화번호로_오인되지_않는다() {
            assertFalse(masker.containsSensitive("2024-1234 주문 어디쯤?"));
            assertEquals("2024-1234 주문 어디쯤?", masker.mask("2024-1234 주문 어디쯤?"));
        }

        @Test
        void 금액_12340원은_가려지지_않는다() {
            assertEquals("가격은 12340원 입니다", masker.mask("가격은 12340원 입니다"));
        }
    }

    @Nested
    @DisplayName("누락 (FN) — 규칙 기반의 한계를 명시적으로 드러낸다")
    class FalseNegative {

        @Test
        void 행정구역_4단_도시구동_주소는_놓친다_보완필요() {
            // 정규식은 "시/도 + (구/군/시) + 동/.." 3단만 처리한다.
            // "도 + 시 + 구 + 로"처럼 (구/군/시)가 두 번 나오는 4단 주소는 못 잡는다(FN).
            // → README 보완안: (구|군|시) 토큰을 1~2회 반복 허용하도록 일반화.
            assertFalse(masker.containsSensitive("경기도 성남시 분당구 정자일로 95"));
            // 참고: "서울 종로구 종로3가 102"는 '종로'의 '로'가 도로명으로 매칭돼 오히려 탐지된다(의외의 TP).
            assertTrue(masker.containsSensitive("서울 종로구 종로3가 102"));
        }
    }
}
