# ADR-005 — 대화 메모리(윈도우)는 감사 로그가 아니다

## Status
Accepted (개념 경계 정립)

## Context
4부 자료는 "감사·법적 보관"을 JDBC 전환의 이유로 든다. 그러나 `MessageWindowChatMemory` + `JdbcChatMemoryRepository` 조합의 실제 동작을 확인하니, 그것만으로는 **전체 감사 로그가 되지 않는다.**

## 근거 (공식 문서 + 동작)
- `JdbcChatMemoryRepository.saveAll(conversationId, messages)`는 해당 세션 행을 **DELETE 후 INSERT(replace)** 한다.
- `MessageWindowChatMemory`는 **윈도우(최근 N개)만** repository에 넘긴다.
- ∴ DB에는 **세션당 최근 ≤N개만** 남는다. 윈도우 밖으로 밀려난 옛 메시지는 **DB에서도 삭제**된다.
- Spring AI 공식 문서: *"The `ChatMemory` abstraction is designed to manage the chat **memory** … it is not the best fit for storing the chat **history**."*

## Decision
- **메모리(최근 맥락)** 와 **히스토리(전체 기록)** 를 분리한다.
- ChatMemory/JDBC는 *맥락* 용도(세션당 N개)로 쓴다.
- 진짜 감사가 필요하면 **append-only 히스토리 로그**(윈도우 미적용, 별도 테이블/스토리지)를 둔다.

## Consequences
- "JDBC로 바꾸면 감사가 된다"는 단순화는 틀렸다 — 윈도우가 옛 메시지를 지운다.
- 3단계 실측에서 `kill -9` 후 마지막 메시지 1건이 미플러시된 것도, 이 저장 경로가 **감사 보증(내구성·완전성)** 을 목표로 설계되지 않았음을 보여준다([ADR-003](ADR-003-inmemory-vs-jdbc.md)).
- 프라이버시: 영속화 순간 개인정보 처리자가 된다 → 마스킹·TTL·암호화는 별도 결정(Round 5 Guardrail).
