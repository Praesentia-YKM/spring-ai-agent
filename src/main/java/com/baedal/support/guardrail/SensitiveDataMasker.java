package com.baedal.support.guardrail;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 5주차 — 민감 정보 마스킹기 (Output Guardrail의 도구).
 *
 * <h3>왜 "제거"가 아니라 "대체"인가</h3>
 * 전화번호를 통째로 지우면 "연락처 () 로 안내드릴게요" 같은 깨진 문장이 남는다.
 * 값만 마스킹하면("010-****-5678") 문장 맥락은 살리고 식별 정보만 가린다.
 *
 * <h3>마스킹은 "정확도가 생명" — 과잉 마스킹의 함정</h3>
 * 주문번호 {@code 2024-1234}, 금액 {@code 12340원}을 전화번호로 오인해 가려버리면
 * 상담 자체가 망가진다. 그래서 전화번호 패턴은 <b>앞자리 {@code 01[016789]}</b>를 강제해
 * "20"으로 시작하는 주문번호와 구분한다. (단위 테스트로 이 경계를 못 박는다.)
 */
@Component
public class SensitiveDataMasker {

    // [2단계-A] 휴대폰 — 앞자리 01[016789] 강제로 주문번호(2024-…)와 분리.
    //   그룹: (앞 3자리)(구분자)(가운데 3~4자리)(구분자)(뒤 4자리)
    //   가운데 그룹만 ****로 치환 → "010-1234-5678" → "010-****-5678"
    private static final Pattern PHONE_KR = Pattern.compile(
            "(01[016789])([\\s-]?)(\\d{3,4})([\\s-]?)(\\d{4})");

    // [2단계-B] 이메일 — 로컬파트 첫 글자만 남기고 ***, 도메인은 유지.
    //   "len@woowahan.com" → "l***@woowahan.com" / "a@b.co" → "a***@b.co"
    private static final Pattern EMAIL = Pattern.compile(
            "([A-Za-z0-9._%+-])([A-Za-z0-9._%+-]*)@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    // [2단계-C] 주소(행정동 기반) — "시/도 + (구/군/시) + 동/읍/면/리/로/길 + 번지" 3단.
    //   매칭되면 통째로 "[주소 비공개]"로 치환한다.
    //   한계(README 보강 대상): 행정구역이 4단인 "도 + 시 + 구 + 동/로" 주소
    //   (예: "경기도 성남시 분당구 정자일로 95")는 첫 (구/군/시) 뒤에 또 '구'가 와서 놓친다(FN).
    //   보완안: (구/군/시) 토큰을 1~2회 반복 허용하도록 패턴을 일반화한다.
    private static final Pattern ROAD_ADDRESS = Pattern.compile(
            "(서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|전북|전남|경북|경남|제주)" +
            "(특별시|광역시|특별자치시|특별자치도|도|시)?\\s*" +
            "\\S+(시|군|구)\\s+" +
            "\\S+(동|읍|면|리|로|길)\\s*" +
            "[\\d-]+");

    private static final String ADDRESS_MASK = "[주소 비공개]";

    /** 셋 중 하나라도 발견되면 true (Output Guardrail이 "마스킹할지" 판단). */
    public boolean containsSensitive(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return PHONE_KR.matcher(text).find()
                || EMAIL.matcher(text).find()
                || ROAD_ADDRESS.matcher(text).find();
    }

    /** 전화 → 이메일 → 주소 순서로 모두 마스킹한 새 문자열을 돌려준다(원본 불변). */
    public String mask(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String masked = maskPhone(text);
        masked = maskEmail(masked);
        masked = maskAddress(masked);
        return masked;
    }

    /** "010-1234-5678" → "010-****-5678" (가운데 그룹만 ****). */
    public String maskPhone(String text) {
        Matcher m = PHONE_KR.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            // 가운데 자리수만큼 *를 찍지 않고 고정 4개(****)로 — "몇 자리인지"조차 숨긴다.
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    m.group(1) + m.group(2) + "****" + m.group(4) + m.group(5)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** "len@woowahan.com" → "l***@woowahan.com" (첫 글자 + *** + @도메인). */
    public String maskEmail(String text) {
        Matcher m = EMAIL.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    m.group(1) + "***@" + m.group(3)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 행정동 기반 주소 → "[주소 비공개]". */
    public String maskAddress(String text) {
        return ROAD_ADDRESS.matcher(text).replaceAll(ADDRESS_MASK);
    }
}
