package com.baedal.support.guardrail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 5주차 — Input Guardrail (order=5, 체인 가장 바깥).
 *
 * <h3>역할</h3>
 * Memory·RAG·LLM에 닿기 <b>전</b>에 입력을 검사해, 공격/비정상 입력이면
 * {@link CallAdvisorChain#nextCall} 을 호출하지 않고 즉시 응답을 만들어 돌려준다(short-circuit).
 *
 * <h3>왜 order=5 (가장 바깥)인가</h3>
 * <pre>
 *   InputGuardrail(5) → Memory(10) → RAG(20) → OutputGuardrail(50) → Performance(100) → LLM
 *        └ 차단 시 여기서 끝. 아래 단계는 한 줄도 실행되지 않는다.
 * </pre>
 * Spring AI는 <b>order가 낮을수록 바깥(먼저 실행)</b>이다. Input이 Memory(10)보다 앞이라야:
 * <ul>
 *     <li>공격 입력이 Memory에 <b>대화 이력으로 저장되지 않는다</b>(오염 방지).</li>
 *     <li>RAG 임베딩·LLM 호출이 <b>아예 안 일어나</b> 토큰 비용이 0이다(숙제 1단계 핵심).</li>
 * </ul>
 * 만약 Input을 Memory 뒤에 두면, 막을 입력으로도 Memory 조회·프롬프트 조립이 먼저 돌아
 * 비용과 오염이 발생한다.
 *
 * <h3>왜 정규식인가 — 그리고 한계</h3>
 * 분류 LLM/Moderation API는 (1) 호출 비용·지연이 추가되고 (2) 그 자체가 또 공격 표면이다.
 * 교육 단계에서는 "원리"를 보기 위해 정규식으로 시작한다. 정규식은 공백/제로폭문자/번역
 * 우회에 약하므로(FN), 실무에서는 Rebuff·LLM Guard 같은 전용 레이어를 앞단에 둔다.
 * 반대로 너무 공격적인 패턴은 정상 질문까지 막는다(FP) — 숙제에서 FP/FN 사례를 찾는다.
 */
@Slf4j
@Component
public class InputGuardrailAdvisor implements CallAdvisor {

    // [1단계-A] 최대 입력 길이 — 결정: 2000자.
    //
    // 근거:
    //   - 실제 상담 문의가 2000자를 넘는 경우는 거의 없다(보통 1~3문장).
    //   - 너무 낮게(예: 200) 잡으면: 주문 내역을 길게 붙여넣는 정상 고객을 막는다(FP).
    //   - 너무 높게(예: 50000) 잡으면: 8000자짜리 스팸/프롬프트 폭탄이 그대로 LLM에 들어가
    //     입력 토큰 비용이 폭증한다(DoS 관점). 길이 컷은 "비용 상한"이다.
    //   - 숙제 1단계 시나리오 4는 5000자 입력 → 2000자 상한에 걸려 INPUT_TOO_LONG으로 차단된다.
    private static final int MAX_INPUT_CHARS = 2000;

    // [1단계-B] Prompt Injection 패턴 (대소문자 무시).
    //
    // 각 패턴은 "역할 탈취 / 규칙 누설 / 탈옥"의 대표 시그니처다. 정상 도메인 질문
    // ("환불 규칙 알려주세요")까지 막지 않도록, 규칙 누설 패턴은 "너의/시스템/내부 + 규칙"이나
    // "규칙 + 전체 + 출력"처럼 의도가 분명한 형태로만 좁혔다(FP 최소화).
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            // 시스템 프롬프트 유출 유도
            Pattern.compile("system\\s*prompt", Pattern.CASE_INSENSITIVE),
            Pattern.compile("시스템\\s*프롬프트"),
            Pattern.compile("(프롬프트|지시문|instruction).{0,10}(출력|보여|공개|복사|그대로)"),
            // 이전 지시 무시 / 규칙 무시
            Pattern.compile("ignore.*(previous|prior|above).*(instruction|rule)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(이전|앞의|위의|모든).{0,6}(지시|규칙).{0,6}무시"),
            // 탈옥 / 제약 해제 모드
            Pattern.compile("jailbreak|DAN\\s*mode", Pattern.CASE_INSENSITIVE),
            Pattern.compile("개발자\\s*모드|제약\\s*없는|탈옥"),
            // 역할 재정의
            Pattern.compile("너는\\s*이제|넌\\s*이제|지금부터\\s*너는"),
            Pattern.compile("now\\s*you\\s*are|you\\s*are\\s*now", Pattern.CASE_INSENSITIVE),
            // 내부 규칙 누설 (의도가 분명한 형태로 한정 → FP 방지)
            Pattern.compile("(너의|당신의|시스템|내부)\\s*(규칙|지침|프롬프트)"),
            Pattern.compile("your\\s*(system\\s*)?(rules|prompt|instructions)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("규칙.{0,10}(전부|전체|모두|싹\\s*다).{0,10}(출력|알려|보여|공개)")
    );

    private static final String INJECTION_FALLBACK =
            "고객님, 저는 주문·배달·취소·환불·쿠폰 관련 상담만 도와드릴 수 있어요. " +
            "필요하신 내용을 말씀해 주시면 도와드리겠습니다.";

    @Override
    public String getName() {
        return "InputGuardrailAdvisor";
    }

    @Override
    public int getOrder() {
        // [1단계-C] order=5 — 체인 가장 바깥. Memory(10)보다도 앞이라야
        // 차단 시 Memory 저장·RAG·LLM이 아예 실행되지 않는다(비용 0 / 오염 0).
        return 5;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String userInput = extractUserText(request);
        GuardrailResult result = check(userInput);

        if (!result.allowed()) {
            // 차단 입력 자체는 평문이므로 앞 40자만, 그것도 WARN으로만 남긴다(로그 폭주/유출 방지).
            log.warn("[InputGuardrail] 차단 — reason={} | input(앞 40자)=\"{}\"",
                    result.reason(), preview(userInput));
            return shortCircuit(request, result.fallbackMessage());  // ← chain.nextCall 없음 → LLM 호출 0
        }

        return chain.nextCall(request);  // 통과 시에만 다음 Advisor(Memory)로 진행
    }

    /**
     * [1단계-D] 입력 1건을 검사한다. (Advisor와 분리해 단위 테스트가 가능하도록 public)
     * <p>검사 순서: 빈 입력 → 길이 초과 → Injection 패턴. 싸고 확실한 것부터 본다.
     */
    public GuardrailResult check(String input) {
        if (input == null || input.isBlank()) {
            return GuardrailResult.block("EMPTY_INPUT",
                    "문의 내용을 입력해 주세요. 어떤 도움이 필요하신가요?");
        }
        if (input.length() > MAX_INPUT_CHARS) {
            return GuardrailResult.block("INPUT_TOO_LONG",
                    "문의가 너무 길어서 처리할 수 없습니다. 핵심 내용만 짧게 다시 보내주세요.");
        }
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(input).find()) {
                return GuardrailResult.block("PROMPT_INJECTION", INJECTION_FALLBACK);
            }
        }
        return GuardrailResult.allow();
    }

    /**
     * [1단계-E] LLM 호출 없이 응답을 수동 조립한다.
     * <p>{@link CallAdvisorChain#nextCall}을 부르지 않고 {@link ChatResponse}를 직접 만들어
     * 돌려주는 것이 short-circuit의 핵심이다. context는 그대로 이어 붙여 뒤 단계의 가정을 깨지 않는다.
     */
    private ChatClientResponse shortCircuit(ChatClientRequest request, String fallbackMessage) {
        AssistantMessage message = new AssistantMessage(fallbackMessage);
        Generation generation = new Generation(message);
        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(generation))
                .build();
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(request.context())
                .build();
    }

    /** 가장 최근 UserMessage의 텍스트를 꺼낸다(Input 단계라 Memory가 아직 안 붙어 원문 그대로). */
    private String extractUserText(ChatClientRequest request) {
        return request.prompt().getInstructions().stream()
                .filter(m -> m instanceof UserMessage)
                .reduce((first, second) -> second)   // 마지막 user 메시지
                .map(Message::getText)
                .orElse("");
    }

    private String preview(String input) {
        if (input == null) {
            return "";
        }
        String oneLine = input.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 40 ? oneLine : oneLine.substring(0, 40) + "…";
    }
}
