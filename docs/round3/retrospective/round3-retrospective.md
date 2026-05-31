# Round 3 회고 — 대화 맥락 관리와 메모리 설계

## 🟢 아하 모먼트
1. **"기억은 모델 안이 아니라 서버 바깥에 있다."** LLM 호출은 stateless 순수 함수라, 같은 모델이 동시에 수천 명을 응대한다 — 모델 안에 고객별 기억 칸을 둘 수 없다. 그래서 transcript를 서버가 들고 매 호출마다 다시 끼워 넣는다. `Repository`·`Advisor`·`conversationId`를 우리가 직접 만들어야 한다는 사실 자체가 "기억이 바깥에 산다"는 증거였다. (DEBUG 로그에서 2회차 요청이 1회차 메시지로 시작하는 걸 눈으로 확인.)
2. **합성(composition)이 저장소 교체를 공짜로 만든다.** `Advisor → ChatMemory → Repository`가 has-a로 포개진 러시아 인형 구조라, 가장 안쪽 ③만 InMemory→JDBC로 갈아끼우면 ④·⑤는 한 줄도 안 바뀐다. "저장 기술과 정책을 독립적으로 바꾸려고 인터페이스를 쪼갰다"는 말이 코드로 체감됐다.

## 내가 배운 것
- **Memory 3레이어의 역할 분리** — Repository(저장 기술)·Window(크기 정책)·Advisor(흐름 훅)가 각각 "어디에/얼마나/언제"를 담당. 합성으로 묶여 교체가 쉽다.
- **슬라이딩 윈도우는 토큰 천장이다** — maxMessages는 설정값이 아니라 `add()`마다 능동적으로 가위질하는 장치. 2단계에서 B(2)는 평탄·붕괴, A/C는 누적·해결로 갈렸다. "키울수록 주문번호가 창문 밖으로 안 밀려나서 그거가 풀린다."
- **세션 분리는 보안이다** — `X-Session-Id`→`CONVERSATION_ID`. `"default"` 폴백은 단일 테스트엔 안 잡히고 운영에서 터지는 사고. 시나리오 4에서 분리를 직접 증명.
- **InMemory↔JDBC는 운영 조건이 결정한다** — 멀티 인스턴스/재시작/감사. 3단계에서 mem 소실 vs file 생존을 재시작으로 실측.
- **Memory와 Tool의 상호작용** — ToolMessage는 Memory에 저장되지 않는다(USER/ASSISTANT만). 그래서 "그거"가 풀리려면 ASSISTANT 응답 본문에 orderId가 남아 있어야 하고, 반대로 응답이 틀리면(관찰 9) 그 오류가 다음 턴 컨텍스트로 재주입돼 굳는다.

## 의문점
- **SystemMessage가 Memory 뒤에 위치**(관찰 2)하는 게 qwen2.5의 중국어 누수(관찰 1)와 정말 인과인가? `PromptChatMemoryAdvisor`(과거를 system에 녹이는 방식)로 바꾸면 언어 안정성이 달라질까?
- **요약(summarization) 전략**을 직접 구현한다면 "언제 요약할지"(턴 수? 토큰 수? 의미 경계?)를 어떻게 판단하나? 윈도우와 요약을 함께 쓰는 하이브리드의 경계는?
- **감사 로그를 별도로 둔다면**(ADR-005) Memory(맥락)와 History(기록)의 쓰기를 어떻게 한 트랜잭션으로 묶나? saveAll 비트랜잭셔널 이슈(#3153)는 운영에서 얼마나 위험한가?
- maxMessages를 **세션이 아닌 고객 단위로 영속**하면 "지난주 그 환불 건"까지 기억하는 기능이 가능 — 그 효용 대비 프라이버시/저장 비용의 분기점은?

## Round 4(RAG)에 시도하고 싶은 것
- **Advisor 체인에 Memory + Q&A를 나란히** — Memory는 "그 주문" 같은 **세션 맥락**, RAG는 "비 오는 날 배달 지연 보상 정책" 같은 **지식**. 둘이 함께 붙으면 *"아까 그 주문, 비 와서 늦었는데 보상 되나요?"* 처럼 **맥락(orderId)+지식(정책)** 을 동시에 요구하는 질문을 커버할 수 있다.
- order 설계: `MessageChatMemoryAdvisor(10)` → `QuestionAnswerAdvisor(?)` → `PerformanceLoggingAdvisor(100)`. RAG 주입이 Memory 주입 뒤/앞 어디여야 토큰·정확도에 유리한지 실측.
- 선행: `pgvector/pgvector` Docker, `ollama pull nomic-embed-text`(이미 받아둠), 메모리·벡터를 **같은 PostgreSQL**로 통합(ADR-003 연장).
