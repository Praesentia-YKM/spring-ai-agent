# ADR-002 — 세션 식별: `X-Session-Id` HTTP 헤더

## Status
Accepted

## Context
Memory는 `conversationId`별로 분리 저장된다. 이 ID를 어디서 가져올지 정해야 한다. 세션 식별이 틀리면 **다른 고객의 대화가 새어** 들어가는 보안 사고가 난다.

## 후보 비교 (배달 상담 도메인)
| 전략 | 장점 | 단점 | 배달 적합성 |
|---|---|---|---|
| 쿠키 / HTTP Session | 브라우저 친화 | 서버 Sticky Session 필요, 앱/API엔 부적합 | 웹 챗봇 UI 한정 |
| **HTTP 헤더 `X-Session-Id`** | 프레임워크·클라이언트 독립, `@RequestHeader`로 간단 | 클라가 값을 정하면 위조 위험 | **앱/웹/API 모두 ✅** |
| JWT 클레임 | 인증과 함께 식별, 서명 검증으로 위조 차단 | JWT 인증 인프라 선행 필요 | 인증 있으면 최선 |
| URL 경로 `/session/{id}/chat` | 명시적 | URL 오염, 로그/캐시에 ID 노출 | 운영 API 비추천 |

## Decision
이번 라운드는 **`X-Session-Id` 헤더** 방식. `@RequestHeader(value="X-Session-Id", defaultValue="default")`로 받아 `a.param(ChatMemory.CONVERSATION_ID, sessionId)`로 연결.

## Consequences / 위험과 대응
- **`defaultValue="default"` 폴백의 위험**(2개 이상):
  1. 헤더를 안 보내는 **구버전 클라이언트**가 전부 `"default"` 한 칸을 공유 → 서로의 대화가 노출.
  2. 어뷰저가 의도적으로 헤더를 빼 **타인의 `"default"` 맥락**을 엿보거나 오염.
  → **프로덕션에선 폴백 금지, 헤더 없으면 400 Bad Request.** (현재는 개발 편의로만 허용.)
- **클라이언트가 ID를 직접 정하는 위험**: 다른 고객의 `sessionId`를 추측·도용 가능.
  → **대응**: 서버가 세션 발급(UUID) 또는 **JWT 서명 검증**으로 소유권 확인. 클라가 임의 문자열을 못 쓰게 한다.
- 실측: 1단계 시나리오 4에서 `s4a`(1234 언급) ↔ `s4b`(맥락 없음) **분리 확인**, `/session/ids`에 6개 세션이 각각 등록됨([raw §1단계](../raw/scenarios.md)).

> 세션 누락이 "테스트는 통과, 운영에서 발견"되는 사고인 이유: 단일 사용자 테스트에선 `"default"` 공유가 드러나지 않는다. [failure-observations 관찰 6](../failure-observations/round3-failure-observations.md).
