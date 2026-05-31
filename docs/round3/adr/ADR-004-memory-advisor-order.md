# ADR-004 — `MessageChatMemoryAdvisor` order(10)와 프롬프트 조립 순서

## Status
Accepted

## Context
Advisor는 체인으로 실행되며 `getOrder()` 값이 **낮을수록 먼저** 실행된다. 이미 `PerformanceLoggingAdvisor`가 `order=100`(체인 바깥에서 LLM 왕복 측정)으로 있다. Memory Advisor의 order를 정해야 한다.

## Decision
`MessageChatMemoryAdvisor`에 `order(10)`. → Memory(10)가 Performance(100)보다 **먼저** 실행된다.

이유: Memory Advisor는 **before 단계에서 과거를 프롬프트에 합친다.** 이게 Performance보다 먼저 일어나야, Performance가 측정하는 `입력 토큰`이 **과거가 합쳐진 뒤의 진짜 입력 크기**를 반영한다. 순서가 반대면 Memory 주입 전 크기를 재게 되어 토큰 관찰(4단계)이 왜곡된다.

## Consequences — 4단계 실측 (프롬프트 주입 증거)
시나리오 1의 2회차 요청 `messages[]` 실제 순서(DEBUG 로그):
```
[ USER:"2024-1234 어디쯤 있어요?",        ← Memory 주입(1회차 USER)
  ASSISTANT:"역삼역 사거리에서 배달 중...",  ← Memory 주입(1회차 ASSISTANT)
  SYSTEM:"[역할]...[대화 맥락 사용 규칙]...", ← System Prompt
  USER:"그거 언제 도착해요?" ]               ← 2회차 새 USER
```
- ✅ Memory가 프롬프트 조립 시점에 과거를 끼워 넣음이 증명됨(2회차 입력이 1회차 메시지로 시작).
- 🔎 **예상 밖 관찰**: `MessageChatMemoryAdvisor`는 주입한 과거 메시지를 **SystemMessage보다 앞**에 둔다. 즉 SYSTEM이 대화 중간에 끼는 형태. qwen2.5의 응답 언어 불안정(중국어 누수, [관찰 1])과 연관 가능성 — 일부 모델은 system 위치에 민감하다.
- 토큰 증가는 [4단계 토큰표](../README.md#4단계--observability)에서 1턴 2,592 → 10턴 2,407(누적)로 관찰.
