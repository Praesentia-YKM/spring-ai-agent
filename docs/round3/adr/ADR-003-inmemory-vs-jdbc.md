# ADR-003 — 저장소 선택: InMemory vs JDBC (의사결정 트리)

## Status
Accepted (기본 InMemory, 조건 충족 시 JDBC)

## Context
`ChatMemoryRepository`는 합성 구조의 가장 안쪽 부품이라 **그 Bean만 교체하면** 정책(④)·흐름(⑤)을 안 건드리고 저장 기술을 바꿀 수 있다. 언제 InMemory로 충분하고 언제 JDBC가 필요한지 기준이 필요하다.

## Decision — 의사결정 트리
| 운영 조건 | Yes면 선택 |
|---|---|
| 로드밸런서 뒤 **멀티 인스턴스**로 뜨는가? | **JDBC**(공유 DB). 로컬 Map은 인스턴스마다 따로라 2번째 요청이 다른 서버로 가면 기억 없음 |
| 서버 **재시작 후에도** 대화가 이어져야 하는가? | **JDBC**(h2:file / RDB) |
| 법적·감사 이유로 상담 이력을 **N년 보관**하는가? | **JDBC + 별도 감사 로그**([ADR-005](ADR-005-memory-is-not-audit-log.md)) |
| **단일 인스턴스 + 분 단위 짧은 세션**인가? | **InMemory** (가장 빠르고 설정 0) |

- **InMemory로 충분한 3조건**: ① 단일 인스턴스 ② 세션이 짧고 휘발돼도 무방 ③ 감사 불필요(데모/사내툴).
- **JDBC가 필요한 3조건**: ① 멀티 인스턴스(LB) ② 재시작/배포 후 연속성 ③ 감사·법적 보관.

## Consequences — 3단계 실측
| 저장소 | 재시작 후 유지? | 근거 |
|---|---|---|
| InMemory (기본) | ❌ | `ConcurrentHashMap`, JVM과 함께 소멸 (1·2단계 거동) |
| `jdbc:h2:mem` | ❌ | 새 JVM = 새 mem DB. 재시작 후 `/session/ids` = `[]` |
| `jdbc:h2:file` | ✅ | 파일 영속. 재시작 후 `jdbc-A` 대화(1234 맥락) 유지 |

H2 테이블 실제 행:
```
CONVERSATION_ID | TYPE      | CONTENT55
jdbc-A          | USER      | 2024-1234 어디쯤 있어요?
jdbc-A          | ASSISTANT | 주문번호 2024-1234의 라이더는 현재 역삼역 사거리...
jdbc-A          | USER      | 그거 언제 도착해요?
```
> ⚠️ `kill -9`(강제 종료) 후 reopen 시 마지막 ASSISTANT 1건이 누락됐다(4행→3행). H2 MVStore 쓰기 버퍼링 + Spring AI saveAll의 비트랜잭셔널 delete+insert(이슈 #3153) 때문. **프로덕션은 graceful shutdown(SIGTERM) + 제대로 된 RDBMS**로 회피. [관찰 8](../failure-observations/round3-failure-observations.md)

## 배달 실운영 DB 선택 + 비기능 요구
- **PostgreSQL** 권장 — Round 4에서 PgVector(Postgres)를 띄우므로 **메모리·RAG 인프라를 한 DB로 통합**. (H2는 교육/테스트용; 운영 부적합.)
- 함께 고려할 비기능 요구 3+: ① **백업**(상담 이력 손실 = 분쟁 리스크) ② **인덱스** `conversation_id, timestamp`(스키마에 이미 포함) ③ **TTL 배치**(90일 자동 삭제) ④ **컬럼 암호화/TDE**(주소·전화번호 평문 저장 방지).
