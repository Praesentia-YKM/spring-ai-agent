# Spring AI Round 2 (Tool Calling) 학습 노트

## 학습 개요
- 학습 일자: 2026-05-24
- 주제: Spring AI Tool Calling — `@Tool`, `@ToolParam`, description 설계, 멱등성, 판단/실행 분리
- 참조 소스:
  - 공식 문서: https://docs.spring.io/spring-ai/reference/api/tools.html
  - Notion Round 2 강의: https://www.notion.so/36a2e1bd53b281449fa5c1abf444072d
  - Notion Round 2 Quests: https://www.notion.so/36a2e1bd53b281d9a04bf5fa4618fe63
  - Notion Round 1 피드백: https://www.notion.so/36a2e1bd53b28136b486e95741115fae
- 기준 버전: Spring AI **1.0.0 GA** / Spring Boot 3.4.1 / Java 17 / Ollama qwen2.5
- 이전 세션: Round 1 (2026-05-17) — ChatClient, System Prompt, Structured Output, Streaming, Advisor

## 큰 그림 프라이머

**한 줄 비유:** 콜센터 상담원(LLM)에게 사내 시스템 접근 코드를 발급. 단, 상담원은 직접 조작하지 않고 "이 버튼 눌러줘"라고 부탁만 함.

**전체 구조:**
```
User: "2024-1234 어디쯤?"
   ▼
ChatClient.prompt().call()        ◀── 사용자가 보기엔 1번 호출
   ▼
┌──── [?] ────┐
│ LLM ⇄ Tool  │                    ◀── 안에서 N번 왕복 (오늘 팔 핵심)
└─────────────┘
   ▼
"라이더가 역삼역 사거리에 있습니다"
```

**Sub-topics:**
1. Tool Calling 메커니즘 — `[?]` 박스 까기
2. `@Tool` + `description` — LLM이 무엇을 보고 호출하나
3. 판단/실행 분리 — 왜 LLM이 DB를 직접 안 만지나
4. 멱등성 — `cancelOrder`가 두 번 호출되면

---

## Q1. LLM은 OrderTools의 존재를 어떻게 아는가?  [sub-topic: 1]

**[질문]**
사용자가 `"2024-1234 어디쯤?"`을 보냈을 때, LLM은 `OrderTools.getDeliveryStatus`라는 메서드가 존재한다는 사실을 어떻게 알게 될까?

A. Spring AI가 LLM 모델 자체에 미리 학습시켜 둠
B. 매 요청마다 Tool 정보를 프롬프트에 끼워서 보냄
C. LLM이 별도의 API로 Spring 서버에 "사용 가능한 도구가 뭐냐"고 먼저 물어봄

**[답변]**
> (대기 중)
