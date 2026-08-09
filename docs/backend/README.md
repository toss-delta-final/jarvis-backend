# 백엔드 명세서

기능 원본은 노션 「기능 정의 - 이소희」. 이 폴더는 그걸 백엔드 구현 가능한 수준으로 구체화한 명세이며, **구현 세션은 코드를 쓰기 전에 01→05를 순서대로 읽는다.** 명세와 코드가 어긋나면 문서를 먼저 고친다.

| 문서 | 내용 |
|---|---|
| [01-order-state-machine.md](01-order-state-machine.md) | 주문/배송/클레임 상태, 전이 규칙, 액션 매트릭스, mock 배송·결제 |
| [02-data-model.md](02-data-model.md) | 전체 테이블 정의, 스냅샷·이벤트 설계, 시드 데이터 요구사항 — 공유용 DDL 스냅샷: [schema.sql](schema.sql) |
| [03-architecture.md](03-architecture.md) | 시스템 구성, 패키지 구조·이름 규칙, 코드 컨벤션(레이어·OOP·동시성·테스트), 판정 기준, 인증, 응답/에러 규약, 기술 스택, 환경변수 |
| [04-api-spec.md](04-api-spec.md) | 전 REST 엔드포인트 (auth/카탈로그/장바구니/주문/마이페이지/채팅/판매자/관리자/internal) |
| [05-llm-contract.md](05-llm-contract.md) | FastAPI 연동 계약 v0.3 — **SSE 직결(티켓) + 추천 목록 콜백/조회 + 판매자 HITL confirm(2026-07-17)**, 잔여 OPEN 있음 |
| [06-implementation-plan.md](06-implementation-plan.md) | Phase 0~6 구현 순서와 단계별 완료 조건 |
| [07-redis-design.md](07-redis-design.md) | Redis에 무엇을 왜 담는가 — 캐시·휘발 상태·조정·계수 4유형, **넣지 않기로 한 것과 그 근거** 포함 |
| [08-kafka-design.md](08-kafka-design.md) | Kafka를 어디에 왜 넣는가 — E-1 스트림 파이프라인 + S-1 실시간 방문자, **넣지 않기로 한 곳과 그 근거** + 전/후 실측(§5) |

모든 설계 결정은 각 문서의 "결정 로그"에 문제→선택지→기준→선택→트레이드오프 형식으로 기록돼 있다.
