# 05. LLM(FastAPI) 연동 계약 — v0.6 (2026-07-23 · 챗봇 요청·SSE 정본 정합)

> **v0.6 (2026-07-23)**: **추천/판매자 챗봇 요청·SSE를 노션 「추천 챗봇 (SSE)」(CH-2) 정본에 정합** — (1) `/chat`·`/seller/chat` 요청 body를 `{sessionId, threadId, message}`로 정정: `threadId` **필수(없으면 400)**, MVP는 `sessionId`와 동일 값·post-MVP는 방(대화창)별 고유 값. `channel`은 body 필드가 아니라 **세션 발급(CH-1)·판매자 세션(S-4) 시점에 확정돼 티켓/세션에 실린다**(일반 CH-1의 `channel:"SELLER"`는 400, SELLER 스트림은 `/seller/chat` 별도 엔드포인트). (2) SSE 프레이밍을 `event: <name>` 명명 이벤트 → **`data: {"type","data"}` envelope**(`event:` 줄 없음)로 정정, `conditions`/`suggestions` 페이로드를 `chips[]`로, `action.reason`을 `PRODUCT_NOT_FOUND|STOCK_INSUFFICIENT|CART_ERROR`(구 `OUT_OF_STOCK` 폐기·품절도 STOCK_INSUFFICIENT로 통합)로, `error.code`를 `LLM_TIMEOUT|LLM_UNAVAILABLE|SEARCH_FAILED|INTERNAL` 4종으로, `budget` 이벤트는 **현재 미구현 → post-MVP 예약**으로 표기. **AI 구현·실행 시스템은 이미 노션대로 동작하며 이 문서가 낡아 있었다**(3-서버 E2E 검증 2026-07-23).

> **v0.5 (2026-07-22)**: **I-20 세션 종료 통지**를 노션 I-20 정본(07-21 개정)에 정합 — 와이어를 camelCase `{sessionId, userId, reason}`로, `userId`(회원 BIGINT) 필수, `X-Internal-Token` 필수, `reason` 값 camelCase(`logout|tabClose|inactivityTimeout|newConversation`), 응답 `202 {status}`, **게스트는 호출 생략**(프로필 대상 아님)으로 확정(§2-1). v0.4의 "I-20 트리거에 가입·로그인 승계 포함" 서술은 게스트 세션이라 **호출 생략으로 정정**(회원 프로필 승격 트리거로 재정의됨 — 구 checkpointer 삭제 부수효과 폐기).

> **v0.4 (2026-07-21)**: 노션 「API 명세서」 정본과 어긋난 표기를 일괄 정정 — S-4 티켓 claim `role:"seller"`+`brandId`(구 `channel`/`brand_id` 폐기), I-9~I-12 소유권 위반 404 존재은닉(구 403), I-9 응답 `{rows,total}`, I-10 201 `{productId,status}`+422 계열, I-11 `changes` = 로그 어휘 문자열 배열(구 `{field,before,after}` 폐기), I-16 노션 본문 형태, I-1 `tags` 제거, I-4 한국어 문자열 명시, I-19 `categoryName`, I-20 실발화 트리거(가입·로그인 승계 포함, IDLE/TAB은 예약). **코드는 이미 노션 기준으로 구현돼 있고 이 문서가 낡아 있었다** — FastAPI 팀은 v0.3 표기로 구현했다면 위 항목을 재확인할 것.

> ⚠️ **이 문서는 LLM 팀과 합의한 백엔드 측 계약이다.** v0.2에서 **SSE FastAPI 직결(티켓 핸드오프) + 추천 2왕복 데이터 흐름**을 반영(노션 「추천 에이전트 흐름」·「JWKS 방식 검토 후 제안」 + LLM 팀 합의). v0.3에서 노션 「API 현재」 확정 번호 체계(04 §10)·**추천 목록 콜백(I-21)+CH-5 조회**·**판매자 2왕복 confirm(HITL)**·**I-20 세션 종료 통지**를 반영. OPEN 표시 항목은 남은 합의 대상.
> 원칙: 대화 내용은 양쪽 다 DB에 저장하지 않는다. 개인화 프로필·**의미검색 임베딩(벡터DB)** 은 LLM 팀 소유. 커머스 데이터의 **쓰기**는 전부 BE internal API 경유, **정형 진실(가격·재고·상태)의 읽기**도 Spring/MariaDB가 원천.

## 0. 왜 이 구조인가 (요약)

- **읽기(응답 표시)는 FE↔FastAPI 직결, 쓰기·신원은 BE 단일 진입점**: 채팅 SSE만 FE가 FastAPI에 직접 연결(성능 — Spring이 스트림당 소켓 2개 릴레이하던 병목 제거). 인증·게스트·쓰기는 여전히 BE 한 곳(03 D5).
- **직결 인증 = 단명 서명 티켓(RS256/JWKS)**: Spring이 채팅 진입 첫 요청에서 신원 검증(회원 JWT/게스트 쿠키) 후 **스트림용 단명 JWT를 RS256 서명**해 발급 → FE가 그 티켓으로 FastAPI 연결 → FastAPI가 **JWKS로 검증만**(신원을 만들지 않음). 상세 §1-0.
- **툴 콜백 패턴**: FastAPI가 상품 검색·담기 등이 필요하면 BE `/internal/*`을 호출. LLM이 커머스 DB에 직접 붙지 않으므로 스키마 변경이 서로를 깨지 않는다.
- **DB 소유 분리**: 커머스 DB(MariaDB)=Spring 전용(D7). **벡터DB(임베딩)=FastAPI 소유·직접 접근**(공개 카탈로그라 D7 예외 — 03 D-분산9). 정형 필터·카드 진실은 Spring, 의미 리랭킹은 FastAPI.
- **인증 토큰 2종**: ① 스트림 티켓(RS256, FE→FastAPI, JWKS 검증) ② `X-Internal-Token`(FastAPI↔BE 콜백, 공유 시크릿·각자 .env).

## 0-1. 권한 모델 — internal에 역할(Role) 검사가 없는 이유

- **신원 검증은 `/api` 경계에서 JWT로 딱 한 번.** internal 요청의 `userId`/`brandId`는 FastAPI가 주장하는 값이 아니라, BE가 JWT를 검증한 뒤 채팅 요청에 실어 보낸 값의 **메아리**다. FastAPI는 신원을 만들어내지 않고 되돌려줄 뿐 — 신뢰의 근원은 항상 JWT.
- **역할 검사 대신 능력 화이트리스트.** 위험 3축 — ① 금전/비가역성 ② 타인 영향(피해가 본인 계정 밖으로 나가는가) ③ 권한 변경(클레임 승인·후기 삭제 등 규칙을 바꾸는 행위) — 에 걸리는 능력은 이 표면에 존재하지 않는다. 검사할 권한이 없는 게 아니라 검사가 필요한 문 자체가 없음.
- **사용자 JWT를 FastAPI에 위임하는 것은 금지.** 위임하는 순간 LLM의 권한 상한이 "이 6개"에서 "그 사용자가 할 수 있는 전부"로 확장되고, 프롬프트 인젝션의 피해 반경이 같이 커진다.
- **3축에 걸리는 능력이 필요해지면** (예: 고도화의 "주문까지 자동화") 거절이 아니라 위험을 깎아서 문턱 아래로 내린다. 검토 순서: ① **초안 + 사용자 확인** — 준비(주문서 초안 생성)는 internal 신규 문으로, 실행은 FE 확인 UI에서 사용자 본인의 JWT로 `/api` 호출 ② 스코프·한도·시간이 박힌 일회용 권한 ③ 행위의 가역화(취소 가능 시간창). 어떤 경우에도 최종 위험 행위가 서비스 토큰만으로 실행되게 하지 않는다.
- **확정 예외(2026-07-17) — 판매자 챗봇 쓰기(I-10/I-11/I-12)**: confirm 후 FastAPI가 서비스 토큰으로 실행하는 유일한 쓰기 경로. 판매자의 `[적용]` 승인(HITL)이 ①의 "사용자 본인 JWT 실행"을 대신하며, 대체 통제는 draftId 바인딩(보여준 것 == 실행) + brandId 경로 하드게이트(티켓 클레임 유래·Spring 소유권 재검증) + 대기 TTL. 이 예외는 판매자 **자기 브랜드 상품**에만 적용 — 3축(금전/타인 영향/권한 변경)에 걸리는 능력으로 확장 금지.

## 1. 채팅: FE → FastAPI (직결 · 티켓)

### 1-0. 스트림 티켓 핸드오프 (RS256/JWKS) — 03 D5

직결 구조라 FastAPI가 "들어오는 요청의 신원"을 직접 확인해야 한다. 신원 검증의 소유자는 **여전히 Spring** — FastAPI는 검증만 한다.

```
FE → Spring   : 채팅 진입 (회원=JWT AT / 게스트=guest_id 쿠키)  ── CH-1에 얹음
Spring        : 신원 확인 후 스트림용 단명 JWT 발급 (RS256 서명)
FE → FastAPI  : 그 티켓으로 SSE 연결
FastAPI       : JWKS로 signature·exp·iss·aud·scope 검증 → 스트리밍
```

- **발급**: 세션 발급(CH-1)에 얹어 티켓 동시 반환 — 추가 왕복 없음. private key는 **Spring만** 보관·회전.
- **재발급(CH-1b)**: 티켓 만료로 FastAPI가 401을 주면 FE는 CH-1b(`POST /api/chat/tickets` — 세션 유지, 티켓만 재발급) 호출 후 **1회 재시도**. 세션까지 없으면 404 `SESSION_NOT_FOUND` → CH-1로 새 세션(04 §6).
- **JWKS**: Spring이 `GET /.well-known/jwks.json`로 public key 목록 제공, FastAPI는 `kid`로 키를 찾아 검증(캐싱 + `kid` miss 시 refetch — Spring이 잠깐 죽어도 캐시로 동작).
- **티켓 claim**:
```json
{
  "sub": "123",                 // userId 또는 guestId
  "sub_type": "member",         // member | guest
  "sessionId": "550e8400-...",  // [2026-07-31] 발급 대상 세션 — threadId는 싣지 않는다
  "iss": "jarvis-spring-auth",
  "aud": "jarvis-fastapi-ai",
  "scope": "chat:stream",       // SELLER도 동일 scope — 판매자는 role/brandId claim 추가(아래·§I-6b)
  "exp": 1720000000             // 발급 후 30~60초 (현행 설정 60초)
}
```
- **`sessionId`를 서명에 싣는 이유(2026-07-31 · CHAT-SESSION D7)**: 종전엔 신원만 담고 세션은 클라이언트가 body로 주장했다. 그러면 ① 임의 문자열로 FastAPI 쪽 맥락을 무한 생성할 수 있고 ② CH-7 승계 직후에도 60초 남은 구 게스트 티켓을 티켓만 보고 거부할 수 없다. FastAPI는 `/chat` body의 `sessionId`가 이 claim과 다르면 거부하고, 이 값으로 stable `context_id`를 파생한다(jarvis-ai #187 · api-spec v0.17.0 §3.5.1). **`threadId`는 여전히 싣지 않으므로 "티켓 1장 = 한 접속의 여러 방" 원칙은 유지**된다.
- **신원은 티켓에서, body가 아님**: `userId`/`guestId`/`brandId`는 FastAPI 툴 인자·요청 body로 받지 않고 **티켓 `sub`/claim에서** 취한다(클라이언트 주장 무시 — 인젝션 차단). `/internal` 콜백엔 이 값을 **메아리**로 실어보낸다(§0-1).
- **왜 전권 AT 직접 안 씀**: `EventSource`(GET)는 커스텀 헤더가 안 실려 AT가 쿼리스트링 노출 → 30~60초 read-only 티켓만 내보내 유출 피해를 "스트림 1회"로 봉쇄. 게스트도 동일 경로로 발급(`sub_type:guest`).
- **연결 시점 인증**: 티켓이 만료돼도 이미 열린 스트림은 유지(SSE 관례 — 스트림 자체가 LLM 응답 1회 분량).

### 1-1. 추천 챗봇 `POST {LLM_SSE_URL}/chat` (FE 직결, `Authorization: Bearer <티켓>`)

```json
{
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",  // Spring(CH-1) 발급·TTL 관리(10분 sliding). CH-1은 멱등 — 활성 세션이 있으면 같은 값이 온다
  "threadId": "550e8400-e29b-41d4-a716-446655440000",   // 대화 스레드(방) 키 — 필수(없으면 400). MVP: sessionId와 동일 값 / post-MVP: 방별 고유(FE 생성)
  "message": "유럽여행 가는데 필요한 거 추천해줘"
}
```

- **`threadId` 필수** — 없으면 400 `BAD_REQUEST`. MVP에선 `sessionId`와 같은 값을 싣고(session=대화), post-MVP에서 한 접속 아래 방(대화창)을 분리하며 방마다 고유 값으로 분화한다(계약은 지금부터 이 필드를 유지). 동시 스트림 409 기준도 MVP=`sessionId` / post-MVP=`threadId`(방).
- **`channel`은 body 필드가 아니다** — 대화 종류(SHOPPING/CS/SELLER)는 **세션 발급 시점**에 확정된다. SHOPPING/CS는 CH-1(`POST /api/chat/sessions`, body `channel`)에서, SELLER는 S-4(`POST /api/chat/seller/sessions` — 입구 자체가 SELLER 전용)에서 정해져 티켓 claim·세션에 실리고, SELLER 스트림은 `/seller/chat` 별도 엔드포인트로 간다(일반 CH-1의 `channel:"SELLER"`는 400).
- **신원(userId/guestId)은 body에 없다 — 티켓 claim(`sub`/`sub_type`)에서** 취한다(§1-0). 게스트면 `sub_type:guest`, 개인화 없이 응답.
- 멀티턴 맥락은 sessionId 기준으로 **FastAPI가 인메모리/자체 스토어에 유지** (BE는 메시지를 저장하지 않음). 세션 종료 시 Spring이 **I-20 `POST {LLM_BASE_URL}/events/session-end`** 로 정리 통지(§2-1) — 트리거: **로그아웃 하나뿐**(노션 I-20 정본 2026-07-30 — 새 대화는 CH-1을 부르지 않게 되어 사유 소멸, 가입·로그인 게스트 승계는 게스트라 원래 미발화). 유휴·탭 종료는 통지 없이 TTL 소멸(FastAPI 자체 TTL 백스톱 §3 — enum의 IDLE_TIMEOUT·TAB_CLOSE는 예약). *(구 `DELETE {LLM_BASE_URL}/sessions/{id}` 안(OPEN이었음)을 대체 — 2026-07-17 확정. sessionId 형식도 UUID로 합의 완료)*
- 카테고리 진입(메인에서 카테고리 클릭)은 별도 필드 없이 message로 전달: FE가 `"[카테고리] 주방용품 보여줘"` 형태로 첫 메시지 구성. **(OPEN: 전용 필드로 분리할지)**

### 1-2. 응답: SSE 스트림

이벤트 타입 9종. **FastAPI가 FE로 직접 발행**한다(직결 — Spring 패스스루 아님). **프레이밍**: 각 이벤트는 `data:` 한 줄에 **envelope**(`{"type","data"}`)로 실린다 — `event:` 줄은 **없다**(type을 data 안에 넣음). 표준 `EventSource`는 GET·헤더 불가라 FE는 **fetch 스트리밍(ReadableStream)** 으로 `\n\n` 분할 → `data:` 파싱 → `JSON.parse` → `switch(payload.type)`. `products.ready` 이벤트는 **카드 페이로드 없음 — `listIds[]` 상관키만** 싣고, 카드는 FE가 목록마다 CH-5(`GET /api/chat/lists/{listId}`)로 Spring에서 조회한다(§1-2-1).

```plain text
data: {"type":"token","data":{"text":"유럽여행이라면 "}}                          // 답변 텍스트 조각 (스트리밍)
data: {"type":"conditions","data":{"chips":[{"field":"priceMax","label":"5만원 이하","value":50000}]}}
                                                                                  // LLM이 발화에서 추출한 조건 칩 — FE가 제거 가능하게 표시
data: {"type":"suggestions","data":{"chips":[{"label":"6만원대까지 볼까요?","relaxation":{"field":"priceMax","value":65000},"estCount":12}]}}
                                                                                  // 완화/되돌리기 제안 칩 (relaxation 또는 revert 정확히 하나, estCount==0 제외)
data: {"type":"products.ready","data":{"sessionId":"550e8400-…","listIds":["3f9a2c1e7b8d4e5fa0c6d1e97b3f8a24"]}}
                                                                                  // 추천 목록 준비 완료 — 카드 없음(listIds[] 상관키만, 목록 수만큼). I-21 콜백 성공 후에만 발행
data: {"type":"action","data":{"type":"CART_ADDED","message":"무선 키보드 1개를 장바구니에 담았어요","cartItemId":55}}
data: {"type":"draft","data":{"draftId":"d-1","op":"update","productId":3,"changes":[{"field":"price","before":"12000","after":"9900"}]}}
                                                                                  // SELLER 전용 — 수정 초안. FE가 diff 카드 + [적용] 버튼 렌더 (§1-3)
data: {"type":"done","data":{"finishReason":"stop"}}                              // stop | zero_result
data: {"type":"error","data":{"code":"LLM_TIMEOUT","message":"잠시 후 다시 시도해주세요"}}
```

- `conditions`: 디자인 시안의 "조건 칩" UI 지원 — `chips[{field, label, value}]`. **칩 X 제거 시 FE는 후속 메시지로 전달** — `message: "[조건 제거] 우아한"` 형태의 규약 문자열(같은 세션이라 LLM이 맥락 유지, 재추천 후 갱신된 conditions·products.ready 재발행). 별도 API 없음.
- `suggestions`: 결과가 없거나 적을 때의 **조건 완화/되돌리기 제안 칩** — 각 칩은 `relaxation{field,value}`(완화) **또는** `revert{category}`(되돌리기) 중 **정확히 하나** + `estCount`(예상 결과 수, `0`이면 제외). 칩 클릭 시 FE가 후속 메시지로 전달(조건 제거 칩과 같은 관성).
- `budget`: **현재 미구현 — post-MVP 예약**. "예산 10만원으로 캠핑 세트" 류 총액 시나리오용(도입 시 `{totalBudget, verifiedSum, withinBudget, droppedItems}` 형태).
- `products.ready`: 구 `products{productId, reason}` 이벤트를 **대체**(2026-07-17) — 추천 ID·이유·카드를 SSE에 싣지 않는다. FastAPI가 **I-21 콜백으로 Spring에 목록 저장 후** `listIds[]`(목록 id 배열 — 다중 목록이면 여러 개)만 발행하고, FE는 목록마다 CH-5로 카드 완결 필드(가격·정가·이미지·rating·reviewCount)를 순서 보존으로 받는다. *(전환 노트: jarvis-ai 현행 구현은 단수 `listId` 1건 발행 — 복수 전환 요청 중이며, 목록이 1개면 둘은 동치)* reason은 이원화 확정(2026-07-18): SSE = 채팅 말풍선용, I-21 콜백 `reasons` = 추천 카드용(CH-5 echo — §I-21).
- **카드 필드의 출처는 BE**: 표시 데이터는 **LLM(SSE)이 아니라 FE가 CH-5로 Spring에서** 받는다. 이유: (a) 가격·재고 같은 **정형 진실은 Spring/MariaDB가 원천**(벡터DB는 배치라 낡을 수 있음), (b) LLM은 "누굴·왜"만 결정하고 표시 데이터는 BE 소유. `productId`는 BE 상품 ID이자 **벡터DB 공유 키** — 카드의 상세 이동은 FE가 `/products/{id}`로. 찜 버튼은 FE가 M-5 직접 호출 — LLM 무관.
- `action`: 담기 등 부수효과의 결과 통지. 실패 시 `type: "CART_ADD_FAILED"` + `reason` 3종 **`PRODUCT_NOT_FOUND | STOCK_INSUFFICIENT | CART_ERROR`** — 재고 부족은 BE I-2 `400 CART_STOCK_INSUFFICIENT`+`availableStock` 매핑(남은 재고 수 안내, 재고 0=품절도 이 코드로 통합 — 구 `OUT_OF_STOCK` 폐기), 수량 상한(합산>99) 초과는 BE `VALIDATION_ERROR`→`CART_ERROR`. 게스트 담기는 허용이라(I-2, 02 D30) `GUEST_NOT_ALLOWED`는 없다.
- `done.finishReason`: `stop`(정상 종료) \| `zero_result`(조건 만족 결과 0건 — FE는 suggestions 칩 중심으로 렌더).
- `error.code`: `LLM_TIMEOUT | LLM_UNAVAILABLE | SEARCH_FAILED | INTERNAL` (종결 이벤트).

### 1-2-1. 추천 데이터 흐름 — 2왕복 리랭킹 (노션 「추천 에이전트 흐름」, 03 §7 ③)

DB가 둘(커머스 MariaDB=Spring / 벡터=FastAPI)이라 조회가 둘로 갈린다:

```
1. FE → FastAPI(SSE)   : 티켓으로 연결, 질문 전달
2. FastAPI             : 정형조건(가격·카테고리·색상·재고·상태) + 의미조건(원룸 적합…) 추출
3. FastAPI → Spring    : [1왕복] 정형조건만 → GET /internal/products/search (I-1)
   Spring → FastAPI    :   MariaDB 후보 조회 — 정형 진실 확정, 리랭킹용 최소필드만 반환
4. FastAPI             : 벡터DB(임베딩)로 의미 리랭킹 → top-K만 LLM 태워 확정 목록 선정(목록당 ≤9) + 이유·응답 생성
5. FastAPI → Spring    : [콜백] 확정 목록 저장 → POST /internal/recommendations (I-21)
   Spring              :   {sessionId, recommendationRequestId, listType, lists[{listId, productIds[](순서 유지), reasons?}]}
                           → Redis(10분, CH-5 조회 전용) + DB(영구, 정본) 양쪽 저장 + recommendation_generated 적재(목록당 1행)
6. FastAPI → FE(SSE)   : token/conditions/…/products.ready{listIds[]}/done — 콜백(5) 성공 후에만 발행
7. FE → Spring         : [2왕복] products.ready 수신 트리거 → 목록마다 GET /api/chat/lists/{listId} (CH-5)
   Spring → FE         :   저장 순서대로 카드 완결 필드(가격·정가·이미지·rating·reviewCount) 부착해 반환 → FE 렌더
```

- **콜백 실패 시 products.ready 발행 금지** — FE가 존재하지 않는 listId를 조회하는 경로를 만들지 않는다. I-21 body·CH-5 응답 스키마는 **2026-07-28~30 확정**(노션 I-21·CH-5 정본 — 다중 `lists[]`·`listType`·목록당 9개).

- **비용 상한**: ~~(a) 라운드1 LIMIT~~ — **2026-07-27 폐기**(§I-1). 판매량순 컷이 의미 리랭킹과 직교해 정답 후보를 잘라냈다. 후보 수 상한 대신 응답 압축(gzip)과 정형 필터 강화로 비용을 다룬다. (b) **top-K 캡** — 벡터 리랭킹 후 LLM에 태우는 후보를 20~30개로 제한(토큰은 벡터검색이 아니라 LLM이 후보를 읽을 때 든다). 실제 토큰 비용은 여기서 걸리므로 (a) 폐기가 LLM 비용을 늘리지 않는다.
- **넉넉히 선정**: 카드 부착(CH-5)에서 HIDDEN·품절이 드롭될 수 있으니 FastAPI는 노출 목표보다 넉넉히 골라 순서를 준다 — 드롭 후에도 목표 개수 유지. **목록당 상한은 9개**(2026-07-30 확정)이므로 그 안에서 여유를 둔다.
- **정합성 경계**: 벡터DB attributes는 **배치 동기화**(§1-2-2)라 낡아도 무방 — 정형 진실(가격·재고·상태)은 3(라운드1)·7(CH-5) 모두 Spring이 확정하므로 거짓 가격·품절이 카드에 안 뜬다.

### 1-2-2. 벡터DB 동기화 (LLM 팀 소유)

- **벡터DB 구성**: `productId`(커머스 DB 공유 키) · `attributes` · `embedding`. 임베딩·의미검색은 FastAPI 소유·직접 접근(03 D-분산9, D7 예외).
- **동기화 방식**: 상품 신규/수정(크롤링 적재·챗봇 수정 I-11 포함)은 **매번 실시간 락 대신 배치로 임베딩 재생성·upsert**(비용 큼 — 크롤링 상품 1만+). 변경분 수집은 **I-17(`GET /internal/products/changes` — since 커서+limit) 배치 pull**(§I-17). 정합성이 치명적이지 않은 이유는 위 "정합성 경계".
- **(OPEN — 협의 중)**: 배치 트리거·주기, 크롤링 파이프라인과의 연결 지점 — **LLM 팀 + 데이터 파이프라인 합의 필요**. (I-17 커서 방식=Base64URL `(updatedAt,id)` keyset·리뷰 포함=`rating`/`reviewCount` 집계는 2026-07-23 확정 §I-17.)

### 1-3. CS/판매자 챗봇

- 요청 body는 추천 챗봇과 동일한 `{sessionId, threadId, message}`(§1-1) — CS는 `/chat`(세션 발급 CH-1의 `channel:"CS"`로 확정), SELLER는 `/seller/chat` **별도 엔드포인트**(S-4 발급, 입구가 SELLER 전용)로 간다. 분기 근거는 **세션·티켓에 실린 채널·role**이지 `/chat` body가 아니다(프롬프트·툴셋 차이는 FastAPI 내부).
- CS: 비로그인(userId null)이면 일반 안내만, 주문 질문엔 로그인 유도 문구로 답변.
- SELLER: `brandId`는 **S-4(세션 발급)에서 BE가 JWT 검증 후 DB에서 도출해 티켓 claim에 박은 값** — 클라이언트(FE)/LLM이 주장하는 brandId는 무시한다(§1-0). 분석 질문은 I-6(매출)·I-7(퍼널)·I-8(계정 이벤트)·I-13~I-16 집계·조회 콜백 사용.
- SELLER 상품 수정 — **draft + 2왕복 confirm, HITL (0-1 검토 순서 ①의 적용 사례)**:
  1. **[스트림1]** LLM이 I-9로 자사 상품을 읽고 수정안 생성 → `draft` 이벤트 발행: `{draftId, op: update|create|delete, productId, changes: [{field, before, after}]}` — field는 `name/description/price/stockQuantity/status`
  2. FE가 채팅 스트림 안에 diff 카드 + [적용]/[취소] 버튼 렌더
  3. 판매자가 [적용] 클릭 → FE가 FastAPI에 **confirm 요청** 전송 → **[스트림2]** FastAPI가 confirm된 draft 내용 그대로 I-10(등록)/I-11(수정)/I-12(삭제)를 호출하고 실행 결과를 이벤트로 발행. confirm 전송 형식(전용 필드 `{action:"confirm", draftId}` vs 특수 메시지) — **OPEN(LLM 확정 대기)**
  - **HITL 안전장치 5종**: ① **draftId 바인딩** — 실행은 confirm된 draftId의 확정 내용만(발화 재해석·재생성 금지) ② **명시 액션만** — 채팅 발화("그냥 수정해줘")는 동의로 인정하지 않음(동의 판정이 LLM 안으로 들어가면 리뷰·문의 등 고객 텍스트 경유 간접 인젝션이 동의를 위조할 수 있다 — 인젝션이 성공해도 피해는 "이상한 초안이 보인다"에서 끝나는 구조) ③ **멱등** — 같은 draftId 재confirm 무해 ④ **brandId 하드게이트** — 티켓 claim 도출 값만 사용, I-9~I-12가 소유권을 반복 검증(타 브랜드 소유·미존재 모두 404 `PRODUCT_NOT_FOUND` — 존재 은닉, 노션 I-11 확정) ⑤ **대기 TTL** — 만료된 초안은 실행 불가(재초안 유도)
  - **삭제는 필수 HITL + soft delete**: I-12는 status=HIDDEN 전환만(hard delete 문 없음), STATUS 변경 로그 기록.
  - 상품 수정은 **챗봇 경로(I-11)가 유일**(2026-07-21) — 판매자 직접 수정(구 S-5 PATCH)은 폐기. 검증(price ≤ originalPrice, stock ≥ 0)·product_change_logs 기록은 I-11 소관.
  - 스트림1 단계의 LLM은 "수정했어요" 류 성공 발화 금지(실행 전 성공 환각 방지 — §4 가드레일). 성공 발화는 스트림2의 실제 실행 결과에만 바인딩.

## 2. 콜백: FastAPI → BE `/internal/*`

공통: `X-Internal-Token` 필수. 응답은 BE 공통 envelope. 타임아웃 권장 3s. **여기 없는 쓰기 작업은 존재하지 않는다** (주문 생성·클레임·후기는 LLM이 못 함 — 결제 자동화 범위는 "담기까지").

🔄 **id 타입 (2026-08-06)**: 공개 API는 id를 **문자열**로 내보내지만(04 §0 — JS 안전 정수 초과), **`/internal`은 숫자를 유지한다**. Python은 정밀도 손실이 없어 바꿀 이유가 없고, jarvis-ai의 Pydantic 스키마(`product_id: int`)를 이득 없이 흔들게 된다. **예외 하나** — `CART_OPTION_REQUIRED`의 `error.detail.options[].optionId`는 공개 C-2와 I-2가 같은 CartService·같은 DTO를 지나 문자열로 나간다(§I-2). ⚠️ **LLM 팀 조치 필요**: 판매자 draft 이벤트(§1-3)의 `productId`는 FastAPI가 FE로 직송하므로 BE 변경이 닿지 않는다 — FE가 한 화면에서 숫자/문자열을 섞어 받지 않으려면 그쪽도 문자열로 내보내야 한다. 구매자 추천은 SSE가 카드를 싣지 않아(§1-2-1 경로 B) CH-5로 전부 덮인다.

### I-1. 상품 검색 (추천 1왕복 · 후보 조회) `GET /internal/products/search`
- **역할**: 추천 2왕복 중 **라운드1** — 정형조건으로 MariaDB 후보를 좁혀 **리랭킹용 최소필드**만 반환(1-2-1). 표시 데이터는 안 준다(CH-5 카드 부착 담당).
- query: `keyword?`(상품명+summary+attributes LIKE), `categoryName?`, `minPrice?`, `maxPrice?`, `brandName?`(**리스트 — 반복 파라미터, 하나라도 일치하면 후보**), `color?`(**리스트 — 2026-08-03 개정, 아래**). **정형 진실(가격 범위·재고·판매상태 필터)은 Spring SQL에서 적용** — 살 수 없는 상품은 후보에서 제외.
- 🔄 **`color` 3갈래 판정 (2026-08-03 LLM팀 실측 합의)**: ① 미지정이면 조건 없음 ② **`attributes`에 색상 축이 없으면 통과** — 색상 미상이 카탈로그의 **34%(2,445건)** 라 거르면 그 상품들이 전멸한다 ③ 있으면 **색상 값만** 비교(`json_extract`로 키를 좁힌 뒤 부분 일치 — "그레이"가 "다크그레이"를 잡아야 한다, 실측 75건). 구 구현은 `attributes` JSON **전문**을 LIKE로 훑어 정밀도가 **화이트 37.9%** 였다(오탐 출처: `_extra.visual_features` 설명문). **`color`는 반복 파라미터** — 같은 색의 표기가 789종이라(네이비 636 vs 남색 8) 문자열 하나로는 안 잡힌다. **동의어 확장은 LLM 팀 소관**이고 BE는 받은 값들을 OR로 본다(BE 스키마 변경 없음).
- **2026-07-27 개정 — `size` 삭제 + 후보 수 상한 폐지**: 정형조건에 일치하는 상품을 **전부** 반환한다(§1-2-1 비용상한 (a) 폐기). 판매량순 컷이 의미 리랭킹과 직교해 정답 후보를 잘라내던 문제 때문 — 후보 선별은 정형조건이, 순위는 FastAPI 리랭킹이 담당한다. **응답 순서는 보장하지 않는다**(정렬 제거 — 매칭 전체에 filesort를 거는 비용만 남고, 순서를 남기면 LLM이 순위 신호로 오해할 여지가 생긴다). 정형조건이 하나도 없는 요청은 **LLM 단에서 차단**하므로 BE는 별도 가드를 두지 않는다.
- **`brandName` 부분 매칭**: 존재하지 않는 브랜드명은 무시하고 나머지로 검색, **전부 미존재일 때만 0건** — 브랜드 하나 잘못 넣었다고 추천 전체가 죽지 않게 한다. (`categoryName`은 여전히 단건.)
- 🔄 **`categoryName` 해석 (2026-08-03 개정 — 노션 I-1 기준으로 정렬)**: **LLM은 잎(말단) 카테고리 이름을 그대로 보낸다.** 서버는 정확히 일치시켜 조회할 뿐 상위 개념을 해석하지 않는다. 카테고리는 **도메인(뿌리) + 잎** 2단이고 "대분류"는 별도 행이 아니라 **잎 이름의 접두사**다(잎 이름 자체가 `대분류 > 중분류` 형태 — 예 `거실가구 > 소파`). 따라서 `categoryName=거실가구`는 **그런 행이 없어 0건**이고, 상위 개념으로 찾으려면 **LLM이 해당 잎들로 펼쳐 각각 호출**한다. 도메인명(`parent_id IS NULL`)을 보내면 서버가 하위 잎 전체로 확장하지만 표준 경로는 아니다. ~~구 문구: "대분류명이면 하위 소분류 전체 포함 / LLM이 대분류명을 보내는 게 기본 경로 — 대분류 지정이 0건이 되면 안 됨"~~ — 대분류가 행으로 존재하지 않아 성립하지 않는 서술이라 폐기
- 응답 item: `productId, name, summary, attributes(JSON), categoryName, brandName, price, rating, reviewCount, options, optionCount`. **`price`·`rating`·`reviewCount`는 리랭킹 계산 입력**이라 포함한다(2026-07-27 합의 — "5만원 이내", "평점 몇 점 이상" 조건의 top-k 계산). ⚠️ 나머지 display 필드(`originalPrice`·`imageUrl`)는 계속 제거 — 카드 조회(CH-5)로 이동. FastAPI는 여기서 받은 `productId`로 자기 벡터DB의 embedding을 찾아 의미 리랭킹한다.
- 🔄 **`options` 추가 (2026-08-03 LLM팀 합의)**: **옵션명만** 담는 문자열 배열이다 — 담기용 `optionId`는 I-2가 400 + `options` 목록으로 되돌려주는 경로가 이미 있어 여기선 불필요하다. **최대 20개까지만 싣고, `optionCount`는 자르기 전 전체 개수**다(잘렸는지 판별용). 상한의 근거는 실측 — 옵션 수 p99=40·max=161이고 **20개를 넘는 2.67%가 옵션 바이트의 44.5%**를 차지한다. 같은 DTO를 쓰는 **I-3도 동일하게 나간다**.
- `rating`·`reviewCount`는 review 집계값(02 D9 — 컬럼으로 저장하지 않음). **리뷰 0건이면 `rating: 0.0`, `reviewCount: 0`**(§I-17과 동일 규약). 상한이 없어 id `IN` 배치 집계를 쓸 수 없으므로 **검색 쿼리에서 `left join review` + `group by`로 함께 집계**한다 — `AVG()`는 0행에서 NULL을 반환하므로 0으로 나누는 경로는 만들지 않는다.
- attributes까지 반환하는 이유: LLM이 "린넨 소재만" 같은 세밀 조건을 후처리 필터링할 수 있게(서버는 후보만 좁힘 — 02 D7). 카테고리별 속성 축의 정의는 `category.attribute_schema`(02 D11) — 시드 데이터·벡터DB attributes·LLM 프롬프트가 같은 축을 공유한다.

### I-2. 장바구니 담기 `POST /internal/cart/items`
- body: `{ "userId": 123, "guestId": null, "productId": 1, "optionId": null, "quantity": 1 }` — userId/guestId 중 하나(채팅 요청의 메아리). quantity 1~99 (04 §3과 동일 검증: 입구가 달라도 같은 CartService)
- **게스트(userId null)도 guestId로 담기 성공** (02 D30 — 2026-07-10 개정, 기존 403 유도 폐기). 로그인 유도는 결제 시점의 FE 몫 — LLM은 "장바구니에 담았어요. 주문하실 땐 로그인이 필요해요" 정도로만 안내.
- 옵션 필요한데 optionId 없으면 400 `CART_OPTION_REQUIRED` + options 목록 반환 → LLM이 "어떤 색상으로 담을까요?"로 되물음. **(2026-07-18 구현 확정)** options는 envelope `error.detail.options[{optionId, name, extraPrice}]`로 실린다. **2026-08-06부터 여기 `optionId`는 문자열**(`"42"`) — 공개 C-2와 DTO를 공유해서다(§2 id 타입). 되보내는 요청 body의 optionId는 숫자·문자열 모두 받는다.
- **재고 부족 시 400 `CART_STOCK_INSUFFICIENT` + `error.detail.availableStock`(2026-07-22 추가)** — 합산 후 수량이 재고를 넘으면. LLM은 "재고가 N개뿐이에요"로 안내. 재고는 상품 단위(옵션별 재고 없음, 02 D33)라 옵션 무관하게 상품 재고와 비교. C-2와 동일 CartService·동일 규칙.
- 성공 응답에 cartItemId — action 이벤트에 사용. 행동 이벤트(behavior_events `add_to_cart`)는 서버 적재가 아니라 FE 배치 소관(04 §8 E-1 — 2026-07-17 전환).

### I-3. 인기 상품 `GET /internal/products/popular?size=12`
- 무관 질문 시 카드 영역 유지용. 응답 형식 I-1과 동일.
- 인기 집계는 P-4와 같은 로직을 공유하므로 **품절(재고 0)은 후보에서 빠진다**(04 2026-07-28 결정) — I-1의 `stockQuantity > 0` 필터와 같은 기준이다.

### I-4. 주문 상태 `GET /internal/members/{userId}/orders/status?recent=3`
- 응답: 주문별 `{ orderId, orderedAt, representativeStatus, items: [{ productName, status, statusText }] }` — `statusText`·`representativeStatus` 모두 **한국어 표시 문자열**("배송중" — 노션 I-4 07-18 재확인, LLM이 그대로 인용; enum 코드는 I-19가 담당).
- statusText는 한국어 표시 문자열(예: "배송중") — LLM이 그대로 인용.
- I-4는 **요약 전용** — 상세 목록이 필요한 질문("내 주문 어때?")은 I-19(구매 이력 목록)와 역할 분담.

### I-5. 문의 접수 `POST /internal/inquiries`
- body: `{ "userId": 123, "title": "LLM이 요약 생성한 제목", "content": "챗봇이 정리한 문의 내용" }` — 게스트 403(문의는 로그인 필요, 기능 정의 9번). title·content 모두 LLM 생성(02 D23).
- 문의 단일 채널 원칙: 이 API가 문의 생성의 유일한 경로.

### I-6. 판매자 매출 시계열 `GET /internal/seller/{brandId}/sales?granularity=daily|weekly|monthly|summary` *(구 `…/stats` 대체 — 2026-07-17 재번호)*
- 응답: 매출/주문수 시계열 + `statusCounts`(summary 한정, 4키 고정: 주문 단위 `PAID`/`PAYMENT_FAILED` + 아이템 단위 `CANCELLED`/`RETURNED` — 노션 확정, 2026-07-18), 이상 감지 `isAnomaly`·`deviationPct`(7일 이동평균 대비 ±30%). **LLM에 raw 주문 로그를 주지 않고 집계만 준다** — text2SQL류의 실패 모드(잘못된 쿼리, 타 판매자 데이터 접근)를 계약 수준에서 차단.
- 나머지 판매자 분석 콜백 — I-7(구매전환 퍼널 4단)·I-8(계정 이벤트 집계: **전역**·IP 마스킹·집계 전용)·I-13(행동 이벤트 조회/집계 — **노션 본문 명세 확정(2026-07-18)** — 아래 §I-6b 중 유일하게 노션 정본)·I-14(주문 상태 전이 로그)·I-15(상품 변경 이력 — 품절 신호 = STOCK newValue "0")·I-16(이탈 코호트) — 은 04 §10 표가 확정 목록. 상세 스키마는 아래 §I-6b(2026-07-18 BE 구현 확정 — LLM 합의 시 갱신).

### I-6b. 판매자 콜백 구현 스키마 (2026-07-18 BE 확정 — LLM 합의 대기, 합의 시 이 절이 정본으로 승격)

공통: 기간 파라미터 `from`/`to`(ISO date, 생략 시 최근 30일), 목록 `limit` 기본 100·최대 500. 매출·판매수 집계 규칙은 04 §7 S-1과 동일(PAID 주문의 order_item 중 PENDING/CANCELLED/RETURNED 제외).

- **I-6** `…/sales?granularity=daily|weekly|monthly|summary&from&to` → `{brandId, granularity, from, to, totalSales, totalOrderCount, totalSalesCount, statusCounts{상태:건수}, series[{period, sales, orderCount, salesCount, deviationPct, isAnomaly}]}`. `summary`면 series 없음. 기간 기본값: daily 30일 / weekly 12주 / monthly 12개월. **이상 감지**: 빈 구간 0 채움 후 직전 ≤7개 구간 이동평균 대비 ±30%(`deviationPct` %, 표본 3개 미만이면 null·false). 단 ① 매출 0원 구간은 이상 판정 제외(저볼륨에서 무판매일 전부가 -100% 판정되는 노이즈 방지 — deviationPct는 그대로 반환) ② 이동평균 0에서 매출 발생 시 deviationPct null + isAnomaly true. period 표기 daily `2026-07-18` / weekly `2026-W29`(ISO) / monthly `2026-07`.
- **I-7** `…/funnel?from&to` → `{brandId, from, to, stages[{stage, count, conversionRate}]}` — stage 4종 `product_view/add_to_cart/checkout_start/purchase`, conversionRate는 직전 단 대비 %(첫 단 null). 3단은 checkout_start `properties.productIds`에 자사 상품 포함 여부(주문서 1회=1), 4단은 order_item×product×brand의 PAID 주문 distinct 수.
- **I-8** `/internal/account-events?groupBy=ip|eventType|hour&eventType?&from&to` → `{groupBy, eventType, from, to, buckets[{key, count}]}` — ip는 마스킹(`203.0.113.xxx`, IPv6 프리픽스 2그룹)·상위 100개, hour는 `2026-07-18T02:00` 시각 버킷 오름차순.
- **I-9** `…/products?status?&q?&limit&offset` — offset은 limit 배수 그리드로 스냅(offset/limit 페이지 변환, 기본 limit 20). 응답 `{rows[...], total}`(노션 I-9) — row는 §I-9 본문 필드.
- **I-10** 등록 body: `name·price·stockQuantity` 필수 + **`categoryId` 필수(DB 제약 — 소분류(leaf) 아니면 400 `PRODUCT_CATEGORY_INVALID`)**. `originalPrice` 생략 시 price(무할인), `imageUrl` 생략 시 플레이스홀더, `status` 생략 시 ON_SALE. 응답 **201** `{productId, status}`(노션 I-10). 필수값 누락 422 `MISSING_FIELD`(에이전트 되물음용), `price>originalPrice` 422 `INVALID_PRICE`, 재고 음수 422 `INVALID_STOCK`.
- **I-11** 수정 body(전 필드 optional, 최소 1개): `name, summary, attributes(JSON), description, price, originalPrice, imageUrl, status(ON_SALE|HIDDEN), stockQuantity`. 응답 `{productId, price, stockQuantity, status, changes:["PRICE","STOCK",...]}` — **changes는 로그 어휘 3종(PRICE·STOCK·STATUS) 문자열 배열**(노션 I-11 확정: "changes로 어떤 로그가 남았는지 회신" — 구 `changes[{field,before,after}]` 표기 폐기). 로그 없는 필드(description·originalPrice·imageUrl·attributes·name·summary) 변경은 changes에 나타나지 않음(변경 전후 동일값은 로그 미기록). description은 서버 sanitize 후 저장.
- **I-12** 삭제 → `status=DELETED` 전환 + STATUS 로그 (02 D41 — 구 HIDDEN 전환 폐기). **HIDDEN→DELETED는 정상 전이**라 숨긴 상품도 삭제된다. **이미 DELETED면 409 `ALREADY_DELETED`**(구 `ALREADY_HIDDEN` 폐기 — FastAPI는 409를 이미-처리됨으로 해석). I-11로 삭제 상품을 수정하면 409 `PRODUCT_DELETED`. **I-17은 무변경** — `!= ON_SALE` 조건이라 DELETED가 이미 최소 필드 항목(`status:"HIDDEN"`)으로 실려 나가고, AI 동작(인덱스에서 제거)이 동일하다.
- **I-13** `…/events?from&to(필수)&eventType?(콤마 복수)&productId?&groupBy=product|eventType|date` → groupBy별 응답: `product`면 `{groupBy, rows[{productId, productName, counts{productView, addToCart, checkoutStart, purchaseComplete}, viewToCartRate, uniqueVisitors}], total}`, `eventType`이면 `{groupBy, counts{...}}`, `date`면 `{groupBy, series[{date, productView, addToCart, purchaseComplete}]}`. **노션 본문 확정(2026-07-18)** — 이 항목만 BE 확정이 아니라 노션 정본. 집계 규칙: ① 판매자 스코프 = 자사 브랜드 상품 귀속인데 **귀속 경로가 이벤트마다 다르다**(2026-07-31 명문화) — `product_view`·`add_to_cart`는 `behavior_events.product_id → product → brand.seller_id`, `checkout_start`는 `properties.productIds` JSON 교집합(주문서 1회=1), `purchase_complete`는 `order_item × product × brand`(아래). 상품으로 가는 귀속 경로가 아예 없는 이벤트(session_start/login/search/page_view)만 **제외**(전역 행동은 I-8) ② counts 키는 event_type의 camelCase ③ `uniqueVisitors` = distinct(member_id, guest_id) 게스트 포함 ④ `viewToCartRate` = addToCart/productView, 분모 0이면 null ⑤ 중복 제거 불필요(`client_event_id` UNIQUE로 배제 — E-1 서버 처리 ④) ⑥ `rows` 정렬 = 활동량(counts 4종 합) 내림차순, 동률 시 productId 오름차순. **`purchaseComplete`는 order 기준이다**(2026-07-31 개정 — 구 "이벤트 기준이라 권위가 아니다" 규정 폐기, 이슈 #62 / jarvis-ai#196): purchase_complete 이벤트는 주문당 1회 발사라 `product_id`가 비어 상품 귀속이 구조적으로 불가능하므로, 기간 내 PAID 주문(`paid_at` 기준) 중 해당 상품이 포함된 **주문 건수**(`COUNT(DISTINCT order_id)`)로 센다 — I-7 4단과 같은 정본. 같은 상품을 여러 개 담아도 1이며 **수량 지표가 아니다**(수량은 S-1 `salesCount`, 취소·반품 제외 — 다른 지표라 직접 비교 금지). 부분 취소·반품은 포함, 전량 취소는 주문 상태 전이로 자동 제외(과거 기간 재조회 시 값 감소 가능). `groupBy=eventType` 값은 I-7 purchase 단과 항상 일치하고, `groupBy=product` 값의 합은 그보다 클 수 있다(한 주문에 자사 상품 여러 종이면 상품마다 1 — checkout_start와 같은 성질). 구매만 있고 조회·담기 이벤트가 없는 상품도 rows에 등장. 실패: 400 `INVALID_PERIOD`(from>to·형식 오류) / 400 `INVALID_GROUP_BY`(groupBy·eventType 값 오류).
- **I-14** `…/order-events?toStatus?(콤마 복수)&actorType?&from&to&stats?&groupBy=memberId?&limit` → `{brandId, from, to, rows[{orderId, orderItemId, fromStatus, toStatus, actorType, reason, buyerMemberId, createdAt}], total, byStatus?(stats=true), cancelReasonsTop?(stats=true), rows[{buyerMemberId, orderCount, cancelCount, cancelRatio, maxOrdersPerHour, isSuspicious}](groupBy=memberId)}` — shape 상호 배제(NON_NULL). **[2026-08-06 필드명 정정]** 구 기재 `items`/`statusCounts`/`memberCounts`는 **오기**였다 — 노션 I-14 정본과 구현(`SellerOrderEventsResponse`) 모두 `rows`/`byStatus`/`cancelReasonsTop`다. **[2026-08-06 개정 — I-30 동반]** ① **`orderItemId` 추가** — 아이템 상태 전이면 값, 주문 상태 전이(`PAID` 등)면 `null`. 판매자 발송이 아이템 단위라 이게 없으면 같은 `orderId`의 동일 행이 여러 개로 보여 중복으로 읽힌다 ② **브랜드 스코프 축소** — `orderItemId`가 **있는** 행은 그 아이템이 자사일 때만 포함(구: 자사 아이템이 든 주문의 *모든* 로그 → 타 브랜드 판매자의 발송 시각까지 보였다). `orderItemId`가 `null`인 주문 상태 전이는 종전대로 "자사 아이템 포함 주문" 스코프. 이로써 집계 규칙의 "로그 행·`reason` 귀속은 주문 단위 근사"가 아이템 행에 한해 **정확해진다** ③ `byStatus`는 `COUNT(*)` 유지 — **키마다 층위가 다른 게 정상**이다(`SHIPPING`·`DELIVERED`는 아이템 수, `PAID`·`PAYMENT_FAILED`는 주문 수. 그 상태가 원래 존재하는 층위). ②로 보이는 행이 줄어드는 건 **의도된 축소**이니 LLM 팀은 값 감소를 회귀로 오인하지 말 것. ④ **`cancelReasonsTop`의 `count`도 "취소된 주문 수"에서 "취소된 아이템 수"로 단위가 바뀐다**(`COUNT(*)` 기반) — 사유별 순위는 대체로 유지되나 수치는 커진다. 같은 이유로 **I-16 `preChurnSignals.returnReasonsTop`도 아이템 수**가 된다(`preChurnSignals.cancelCount`·I-14 `memberCounts`의 `cancelRatio`·`isSuspicious`는 `COUNT(DISTINCT order_id)` 기반이라 **불변**). ⑤ 아이템별 클레임 사유가 각 행에 남는다 — 구 규칙은 (주문, from, to)로 묶으며 첫 사유만 남기고 나머지를 버렸다.
- **I-15** `…/product-changes?changeType?(PRICE|STOCK|STATUS)&productId?&from&to&limit` → `{brandId, from, to, items[{productId, changeType, oldValue, newValue, createdAt}]}` 최신순.
- **I-16** `…/churn?from&to(필수)&inactiveDays?(기본 30)` → `{inactiveDays, cohortSize, churnRate, preChurnSignals{cancelCount, returnReasonsTop[{reason,count}], zeroResultSearchSessions, priceIncreaseExposed}, members[{memberId, lastActivityAt, lastLoginAt, sessions30d, preChurnEvent}]}` (**노션 I-16 본문 정본**) — 코호트 = 기간 내 자사 상품 상호작용 회원 중 최근 inactiveDays일 behavior_events 무활동, "마지막 로그인" = account_event_logs.LOGIN_SUCCESS 단일 출처(서버 내부 조인), priceIncreaseExposed = product_change_logs(PRICE 인상) 조인·탈퇴자 제외 없음(MVP). 실패 400 `INVALID_PERIOD`.
- **S-4 티켓**: 기존 claim(§1-0)에 `role:"seller"`, `brandId:<long>` 추가(**노션 CH-6 확정 2026-07-18** — 구 표기 `channel:"SELLER"`/`brand_id`는 폐기, scope는 `chat:stream` 유지). CH-1(`/api/chat/sessions`)로는 SELLER 채널 발급 불가(400) — 입구는 S-4뿐. CH-1b 재발급도 세션에 보관된 brandId로 SELLER 티켓 유지.

### I-9. 자사 상품 목록 `GET /internal/seller/{brandId}/products` *(구 I-7 판매자 상품 상세를 흡수)* + 쓰기 I-10/I-11/I-12
- 응답 item: `productId, name, summary, attributes(JSON), description, price, originalPrice, status, stockQuantity, displayedSalesCount(base_sales_count + order_item 집계)` — query: status/q/limit/offset. 수정 초안(draft) 생성의 읽기 소스(I-1은 추천 리랭킹용으로 슬림하므로 여기서 명시).
- `product.brand_id ≠ brandId`면 **404 `PRODUCT_NOT_FOUND`**(미존재와 동일 — 존재 은닉, 노션 I-11) — 소유권 검증을 internal에서도 반복(productId는 LLM이 채우는 값이라 신뢰 불가). **쓰기(I-10 등록 / I-11 수정 / I-12 삭제)는 HITL confirm 후에만 호출**(§1-3): I-11은 바뀐 필드마다 product_change_logs 기록(동일값 미기록, 응답 changes[]), I-10 등록은 change_logs 미기록, I-12는 soft delete(HIDDEN) 전용.
- `brandId`는 LLM 툴 인자가 아니라 **FastAPI가 티켓 claim/세션 컨텍스트에서 코드로 주입**해야 한다(§4 합의 항목).

### I-17. 상품 정보 배치 pull `GET /internal/products/changes?since=&limit=` (벡터DB 동기화 — §1-2-2)
- FastAPI가 주기 배치로 변경분 pull: `since` 커서 + `limit`(기본 500, 1~500 클램프), 초기 전체 구축은 `since="0"`.
- **정렬·커서(2026-07-23 LLM 합의)**: `(updatedAt ASC, productId ASC)` 고정. `nextCursor`는 마지막 항목의 `(updatedAt, productId)`를 **Base64URL 인코딩**한 불투명 문자열 — AI는 해석하지 않고 다음 `since`로 그대로 전달, Spring이 디코딩해 `updatedAt > cur.updatedAt OR (= AND id > cur.id)` keyset 조회. 잘못된/변조 커서는 **400 `INVALID_CURSOR`** → AI는 `since="0"` 전체 재구축 폴백.
- **응답**: envelope `{success, data:{items[], nextCursor, hasMore}}`. `items[].status`는 `ON_SALE|HIDDEN`(HIDDEN도 포함 — AI가 해당 생성물 삭제). ON_SALE은 `name·category·brand·price·rating·reviewCount·attributes` 동반(평점·리뷰수는 저장 없이 조회 시 집계 — 02 D9, `product.updated_at` 스냅샷), HIDDEN은 `productId·status·updatedAt`만. `hasMore=true`면 `nextCursor` 필수, 빈 결과는 `items=[]`·요청 `since` echo.
- 코드: `ProductService#getChanges`, `ProductChangeCursor`(Base64URL 코덱), `ProductRepository#findChangesSince`.

### I-18. 챗봇 장바구니 조회 `GET /internal/cart`
- userId/guestId 메아리(게스트 허용). 응답 item에 `productName·optionName` 필수(LLM이 그대로 발화), 빈 장바구니도 200(빈 배열).

### I-19. 구매 이력 목록 `GET /internal/members/{userId}/orders`
- CS 챗봇 "내 주문 어때?" 용 — `status` 단일 필터(어휘: `ORDERED|SHIPPING|DELIVERED|CONFIRMED|CANCELLED|RETURNED` — 우리 상태명). 응답은 camelCase·숫자 id, 아이템에 `categoryName`(소분류명 — 노션 I-19), `shippingFee` 항상 0(배송비 없음 확정). I-4(요약)와 역할 분담.

### I-21. 추천 목록 콜백 `POST /internal/recommendations` (확정 2026-07-18 — LLM 합의 · 2026-07-28~30 다중 목록 개정)
- body: `{ "sessionId": "<uuid>", "recommendationRequestId": "<uuid|ulid>", "listType": "PICK_ONE|BUY_ALL", "totalBudget"?, "lists": [ { "listId", "label"?, "productIds": [ … ], "reasons"? } ] }`(순서 유지) — **한 요청에 목록이 여러 개** 올 수 있고, FE는 목록마다 CH-5로 조회한다. **products.ready 발행 전 호출 — 콜백 실패 시 products.ready 발행 금지**(§1-2-1).
- **`listType`**: `PICK_ONE`(목록 안 상품이 서로 대안 — 하나만 산다) / `BUY_ALL`(각자 다른 역할 — 전부 산다). `PICK_ONE`+목록 N개는 **니즈별 추천**("감자탕" → 감자 후보 9개·시래기 후보 9개·뼈 후보 9개), `BUY_ALL`+N개는 **세트 여러 안**. 판단 기준은 예산이 아니다.
- **추천 이유 이원화(합의)**: SSE는 채팅 말풍선용(Spring 무관), 콜백 `reasons`는 우측 추천 카드용 — CH-5 카드에 `reason`으로 echo(없으면 null).
- 검증: sessionId UUID / listId 영숫자·`-`·`_` ≤64(그 외 400 — Redis 키 안전) / **productIds 1~9개(목록당)** / **lists 1~10개**. `reasons`도 목록당 9개·`reason` 200자 상한.
- **저장은 Redis + DB 양쪽**: Redis는 TTL 10분(생성 시점 고정, **CH-5 조회 전용**), DB는 **영구 보존이 정본**(E-1 귀속 검증 + 추천 품질 평가). DB에 남아 있어도 CH-5는 만료 후 404 — 짧은 조회 창을 유지해 노출 표면을 좁힌다. *(2026-08-03 정정: 구 문구 "`listId`가 인증 없이 조회되는 열쇠라"는 낡았다 — CH-5에 **소유자 검증**이 들어가(불일치 404, 존재 은닉) `listId`만으로는 남의 목록을 볼 수 없다. TTL은 주 방어선이 아니라 심층 방어·메모리 관리 몫이며, 세션 TTL과 무관한 자체 값이다 — 07 §2-1)*
- CH-5 응답은 `{listId, recommendationRequestId, listType, label?, itemsDropped, items[카드 완결 필드 + reason]}`(순서 보존, HIDDEN·품절 드롭, 만료 404). `BUY_ALL`이면 `totalBudget`·`sum`·`withinBudget`이 추가된다 — `sum`은 **드롭 후 남은 상품 기준 재계산**(화면 카드 합과 항상 일치), `withinBudget`은 드롭·예산 미발화면 판정 불가라 **null 리터럴**(키 생략 아님). PICK_ONE엔 세 키가 아예 없다.
- **세션 만료 시**: 신원을 해소할 수 없어도 200으로 저장하되 **익명 저장**(`member_id`·`guest_id` 빈 값) — 그 목록은 CH-5에서 조회되지 않고(소유자 미기록 = fail-closed) `recommendation_generated`도 주체 없는 행으로 남는다.
- **listId 엔트로피(2026-07-18 시큐리티 리뷰)**: CH-5는 게스트 허용 공개 조회라 listId가 사실상 bearer 키다 — FastAPI는 listId를 **UUID급 무작위(≥128bit)**로 생성해야 한다(순번·타임스탬프 등 추측 가능한 형식 금지).

### I-24. 챗봇 장바구니 삭제 `DELETE /internal/cart/items/{cartItemId}` (신설 2026-08-05 — AI 이슈 #285)
- 신원은 query `userId`/`guestId` XOR 메아리(게스트 허용) — 위반 시 400 `CART_QUERY_INVALID`(I-18과 같은 코드). 성공은 `data: null`이며 `cartItemId`를 응답에 싣지 않는다(C-4 실측).
- **AI가 I-18로 해소한 id는 인가 근거가 아니다.** 해소와 실행 사이 상태가 바뀔 수 있고 `cartItemId`가 연속 BIGINT라 열거 가능하므로, 소유자 재검증은 실행 시점에 `CartService`가 한다 — 남의 항목이면 403 `AUTH_FORBIDDEN`, 없으면 404 `CART_ITEM_NOT_FOUND`(두 번째 삭제도 404 — **멱등하지 않음**).
- 삭제는 재고·상품 상태를 보지 않는다 — HIDDEN·품절도 삭제되며 `PRODUCT_NOT_FOUND`·`CART_STOCK_INSUFFICIENT`는 발생하지 않는다.
- **복수 삭제는 항목별 반복 호출** — C-4가 bulk API를 두지 않기로 한 것을 internal도 따른다.

### I-25. 챗봇 장바구니 수량 변경 `PATCH /internal/cart/items/{cartItemId}` (신설 2026-08-05 — AI 이슈 #285)
- body `{ "quantity": 3 }`(1~99), 신원은 I-24와 같은 query XOR. 응답은 `{cartItemId, quantity}`(C-3 실측).
- **"3개로 바꿔줘"는 치환이라 I-25, "하나 더 담아줘"는 합산이라 I-2**다. C-3는 보낸 값 자체를 재고와 비교하지만 I-2는 기존+이번 합산을 검증하므로 두 발화를 섞으면 재고 판정이 어긋난다. 합산 권위는 Spring에 있고 I-18 조회는 해석·안내용이다.
- 재고 초과는 400 `CART_STOCK_INSUFFICIENT` + `error.detail.availableStock` → LLM은 "재고가 N개뿐이에요"로 안내. 수량 범위 위반은 400 `VALIDATION_ERROR` + `fields`. 소유권·not-found는 I-24와 동일.
- SSE `action` 확장(`CART_REMOVED`·`CART_QUANTITY_CHANGED` 등)은 **BE 소관이 아니다** — 채팅 스트림은 FE↔FastAPI 직결이라 이벤트 어휘는 AI·FE가 CH-2에서 정한다(2026-08-05 합의).

### I-26~I-28. 챗봇 찜 추가·해제·목록 (신설 2026-08-05 — AI 이슈 #285)
- `POST /internal/wishlist`(body `{userId, productId}`) · `DELETE /internal/wishlist/{productId}?userId=` · `GET /internal/wishlist?userId=`. M-4~6과 같은 `WishlistService`를 재사용한다.
- **역할 검사는 하지 않는다**(§0-1). FE용 M-4~6의 403은 `/api/wishlist/**`에 걸린 `hasRole("USER")` 가드가 만들어내는 값이라 internal 레인에는 대응물이 없다 — 찜은 위험 3축(금전·타인 영향·권한 변경) 어디에도 걸리지 않으므로 검사할 문 자체를 만들지 않는다. 초안의 `403 AUTH_FORBIDDEN` 조항은 이 근거로 삭제하기로 합의했다(2026-08-05).
- **게스트 찜은 존재하지 않는다**(M-4). `guestId`를 받지 않으며 게스트 발화는 internal 호출 없이 AI가 로그인 안내로 degrade한다 — 폐기된 `GUEST_NOT_ALLOWED`를 되살리지 않는다.
- 신원 검증 코드는 **입구에 따라 갈린다**: query 신원(I-27·I-28) 누락은 400 `WISHLIST_QUERY_INVALID`(I-18의 자원별 query code 전례), body 신원(I-26) 누락은 400 `VALIDATION_ERROR` + `fields`(I-2 전례).
- **추가와 해제는 비대칭이다.** I-26은 상품을 조회해 없으면 404 `PRODUCT_NOT_FOUND`지만, I-27은 상품을 보지 않고 찜 행만 찾으므로 **없는 상품과 안 찜한 상품이 모두 404 `WISHLIST_NOT_FOUND`**다 — AI는 응답만으로 둘을 구별할 수 없으니 "찜 목록에 없어요"로 안내를 통일한다. 두 번째 해제도 404(비멱등).
- **HIDDEN·품절도 찜할 수 있다**(M-5) — 재입고를 기다리는 정상 패턴이라 담기(I-2)의 재고 검증과 다르다. 중복 찜은 409 `WISHLIST_DUPLICATE`, 그 앞단 검증을 빠져나간 경합은 409 `RESOURCE_CONFLICT`(AI 처리는 동일).
- I-28은 I-27의 지칭 해소용("어제 찜한 이어폰")이며 조회이므로 `action` 이벤트가 없다 — I-18과 같이 `token` 텍스트로 답한다. 찜이 없어도 200 + 빈 배열이고, 찜한 뒤 HIDDEN·품절이 된 상품도 목록에 남아 "찜해 둔 상품이지만 지금은 담을 수 없어요" 안내에 쓸 수 있다. 페이징 없이 MVP 전량 반환.
- **IDOR 표면은 장바구니보다 작다.** 키가 `(userId, productId)`라 타인의 찜 행을 직접 지목할 수 없고, I-24의 별도 소유자 재검증이 여기서는 "그 회원의 찜 행이 있는가" 판정으로 흡수된다. 단 이 성질은 `userId`를 AI가 검증한 티켓 `sub`에서만 도출한다는 전제(§0-1)가 유지될 때만 성립한다.

### I-29. 자사 주문 조회 `GET /internal/seller/{brandId}/orders` (신설 2026-08-06 — 판매자 챗봇 `get_orders`)

- **S-2의 internal 판**(S-3↔I-9와 같은 구도). "신규주문 뭐 있어?" 현재 상태 Q&A + **I-30 발송 대상 `orderItemId` 해소 경로**. 역할 분리: **I-29 = 현재 상태, I-14 = 전이 이력·집계**.
- query: `status?`(`ORDERED|SHIPPING|DELIVERED|CLAIM` — S-2 탭 어휘, 생략=전체) · `orderId?`(단건 직조회) · `from`/`to`?(`YYYY-MM-DD`, 주문일 기준) · `limit`(기본 20, 1~100) · `offset`(기본 0).
- 응답 `{tabCounts{ALL,ORDERED,SHIPPING,DELIVERED,CLAIM}, rows[{orderId, orderNo, orderedAt, recipientName, paymentMethod, myItemsAmount, status, claimStatus, items[{orderItemId, productId, name, optionName, quantity, price, status, activeClaimStatus}]}], total}` — id는 숫자 그대로(문자열화는 공개 응답 한정).
- **기간은 선택이고 생략하면 전체 주문이다** — 현재 상태 Q&A라 기간 개념이 없고, 잘라내면 오래된 미발송 주문이 빠져 I-30 발송 대상 해소가 막힌다. I-31이 최근 7일 기본인 것과 반대인데 의도된 차이다. `to`는 그날을 포함한다(다음날 0시 미만).
- **`tabCounts`는 탭 선택만 무시하고 기간·`orderId` 필터는 반영한다** — "전량 기준"(S-2)이 뜻하는 건 탭과 무관하다는 것이고, 기간을 준 질의에서는 그 기간 안의 탭 분포가 답이기 때문.
- S-2에서 **상속하는 건 파생 규칙**(대표상태·`claimStatus`·`orderNo`·자사 금액만 집계·타사 아이템 이름/금액 미노출)**이지 응답 필드가 아니다** — `myItemCount`·`representativeProduct`는 싣지 않는다(개수는 `items` 길이, 대표상품은 화면용 장치).
- `activeClaimStatus`는 `CANCEL_REQUESTED|RETURN_REQUESTED|null`이며 claim 테이블이 아니라 **아이템 `status`에서 직접 파생**한다(01 §5 동기 전이). 종결된 클레임(`CANCELLED`/`RETURNED`)은 `status` 자체에 드러나므로 여기선 `null`이다.
- `items[].name`은 **현재 상품명 우선 + 스냅샷 폴백** — S-2 대표상품과 같은 규칙이라 판매자가 상품명을 바꿔도 두 화면이 어긋나지 않는다.
- 실패: 400 `VALIDATION_ERROR`(`status` 어휘 밖 — `PREPARING` 포함 / `limit` 1–100 밖 / 숫자 자리에 문자) · 400 `INVALID_PERIOD`(형식 오류·역전. **누락은 오류가 아니다**) · 401 `INTERNAL_TOKEN_INVALID` · 404 `BRAND_NOT_FOUND` · 500 `INTERNAL_ERROR`. **403은 없다**(§0-1).
  - S-2·I-19가 쓰는 `ORDER_INVALID_PARAM`이 **아니다** — I-24~I-28 internal 전례를 따른다(2026-08-06 결정).
- **자사 주문 0건도 200 + 빈 `rows`·`total: 0`·`tabCounts` 전부 0**이고, `orderId` 직조회가 타사·미존재여도 **404가 아니라 200 + 빈 `rows`**로 존재를 은닉한다 — 에이전트는 "해당 주문이 없습니다"로 안내한다.

### I-30. 발송 처리 `PATCH /internal/seller/{brandId}/order-items/{orderItemId}/status` (신설 2026-08-06 — 판매자 챗봇 `update_order_status`)

- **HITL confirm 후에만 호출**된다(I-12와 같은 등급의 쓰기). 발송은 되돌릴 수 없어서 — `SHIPPING` 이후 역전이·취소 전이가 전부 400이고 `SHIPPING` 구간에선 취소도 반품도 불가(01) — **LLM의 지목이 단독으로 비가역 지점을 통과하지 않게 하는 것이 승인 단계의 목적**이다.
- body `{toStatus, reason?}` — `toStatus`는 상태기계(01) 아이템 어휘, **MVP 유효값은 `SHIPPING` 하나**. `reason`은 선택·200자(`order_status_logs.reason`).
- **이 API가 `ORDERED→SHIPPING`의 유일한 트리거**다(01 D4 개정 — 자동 전이 폐지). 아이템 단위이며 **복수 발송은 항목별 반복 호출**(bulk 없음 — C-4·I-24 전례).
- `brandId`는 티켓 claim에서 오지만 신뢰하지 않고 **실행 시점에 소유권을 재검증**한다(I-11 규칙).
- 응답 `{orderItemId, fromStatus, toStatus, changedAt}` — id는 숫자 그대로.
- 로그는 `order_status_logs`에 `order_item_id`를 채워 1행(actor=`SELLER`) → **I-14로 자동 합류**한다.
- **판정 순서가 계약이다**: ① `toStatus` 어휘 밖(`PREPARING`·`SHIPPED` 포함) → 400 `VALIDATION_ERROR` ② 미존재·**타 브랜드** → 404 `ORDER_ITEM_NOT_FOUND`(403 아님 — 존재 은닉) ③ 이미 `SHIPPING` → **409 `ORDER_ALREADY_SHIPPED`** ④ 그 외 `ORDERED`가 아님(활성 클레임 포함) → 400 `ORDER_INVALID_TRANSITION`. **③을 ④보다 먼저 봐야** "이미 발송됨"과 "발송할 수 없는 상태"가 구분된다.
- **409는 멱등 200이 아니다** — HITL 쓰기는 "이미 된 일"과 "방금 한 일"을 구분해야 에이전트의 거짓 성공 보고를 막는다. LLM은 "이미 발송 처리된 주문이에요"로 안내한다.
- 전이 실패(500 `INTERNAL_ERROR`) 시 **에이전트는 성공 보고 금지**(I-11·I-12 규칙).
- 이중 발송 경합은 조건부 UPDATE(`WHERE status = ORDERED`)가 잡는다 — 검사 이후 다른 실행이 먼저 가져가면 0건이 돌아와 역시 409다.
- **S-4 `draft.op`에 `ship` 추가는 별건**(AI팀·FE 합의 사항). API는 그것 없이도 완성이지만, 확인 카드를 그리려면 그쪽이 따라와야 챗봇에서 실제로 쓸 수 있다.

### I-31. 자사 상품 리뷰 조회 `GET /internal/seller/{brandId}/reviews` (신설 2026-08-06 — 판매자 챗봇 `get_reviews`)

- 읽기 전용이라 **HITL 불필요**(S-4 analysis 레인). "이번 주 리뷰 요약해줘"·"평점 낮은 리뷰 뭐가 문제야"·sales_anomaly 교차(매출 급락일 리뷰 확인)에 쓴다.
- query: `productId?` · `rating?`(1–5 콤마 복수) · `sort`(`latest` 기본 | **`ratingAsc`**) · `from`/`to`? · `limit`(기본 20, 1~100) · `offset`(기본 0) · `stats?`.
- **`status=VISIBLE`만 반환한다** — 신고로 숨겨진 리뷰를 에이전트가 인용하면 사고이므로 구매자 노출(P-3)과 같은 진실만 보게 한다. 숨김 리뷰 열람은 admin 소관.
- **크롤링 리뷰(`member_id IS NULL`, 02 D19)도 포함**한다 — 구매자에게 P-3로 노출되고 있어 판매자에게만 감출 이유가 없다. `authorNickname` = `COALESCE(member.nickname, review.author_name)`.
- **`sort=ratingAsc`는 낮은 순이다. P-3의 `sort=rating`은 높은 순(`order by rating desc`)이라 이름을 갈랐다** — 같은 이름 반대 방향은 사고를 부른다(2026-08-06 확정). 높은 별점만 보려면 `rating=4,5` 필터를 쓴다. 나중에 높은 순이 필요하면 `ratingDesc`를 더한다.
- **기간은 선택이고 생략하면 최근 7일**(`to`=오늘, `from`=6일 전) — I-29가 "전체 기간"인 것과 반대인데 의도된 차이다(리뷰는 기간 질의가 본령이라 전체면 무의미하다). 한쪽만 주면 나머지를 그 값 기준으로 채운다(`to`만 주면 그 날로 끝나는 7일). `to`는 그날을 포함한다.
- 목록 응답 `{rows[{reviewId, productId, productName, rating, content, authorNickname, createdAt}], total}` — id는 숫자 그대로.
- **`stats=true`면 응답 shape 자체가 갈린다**: `{totalCount, averageRating, distribution{"5".."1"}, byProduct[{productId, productName, count, averageRating}]}`이고 `rows`·`total`은 없다.
  - `from`/`to`·`rating`·`productId` 필터는 **집계에도 전부 적용**된다 — 그래야 "1–2점이 어느 상품에 몰렸어?"가 성립한다.
  - `distribution`은 **5~1 키를 항상 전부 담는다**(0 포함) — P-3와 같은 모양이라 LLM이 키 부재를 처리할 필요가 없다.
  - **리뷰 0건이면 `averageRating: null`**(0이 아니다 — I-16 `churnRate` 규칙과 같은 이유로 "평점 0점" 오독 금지), `distribution` 전부 0, `byProduct: []`.
  - `byProduct`는 `count` 내림차순, 동률이면 `productId` 오름차순(I-13 전례), 평균은 소수 1자리 반올림.
- 실패: 400 `INVALID_PERIOD`(형식 오류·역전. **누락은 오류가 아니다**) · 400 `VALIDATION_ERROR`(`rating`이 1–5 밖·숫자 아님 / `sort` 어휘 밖 / `limit` 1–100 밖) · 401 `INTERNAL_TOKEN_INVALID` · 404 `BRAND_NOT_FOUND` · 404 `PRODUCT_NOT_FOUND`(**타 브랜드 소유도 404 — 존재 은닉**, I-11 규칙) · 500 `INTERNAL_ERROR`. **403은 없다**(§0-1).
- **리뷰 0건도 200 + 빈 `rows`·`total: 0`** — 전부 숨김이어도 같다.

## 2-1. 아웃바운드: Spring → FastAPI

### I-20. 세션 종료 통지 `POST {LLM_BASE_URL}/events/session-end` — **방향 예외(Spring→FastAPI)**

FastAPI의 **회원 프로필 버퍼(승격 전 발화)를 지금 프로필로 승격하라는 신호**. 승격 트리거는 셋(로그아웃·새 대화·유휴)이고 그중 **Spring만 감지할 수 있는 로그아웃**을 HTTP로 넘기는 통로가 I-20이다 — 나머지 둘은 FastAPI가 자체 판정해 HTTP 없이 처리한다. best-effort·멱등이며 응답은 **202**(노션 I-20 정본 2026-07-31 개정 반영).

- **인증**: `X-Internal-Token` 필수 — 인바운드 콜백과 동일한 공유 시크릿(§0 ②, `app.internal.token`). 미검증 시 401 `INTERNAL_TOKEN_INVALID`.
- **body** (camelCase): `{ "sessionId": "<uuid>", "userId": <회원 BIGINT>, "reason": "logout" }`
  - `sessionId` — Spring이 UUID로 발급(정규식 제한 없이 불투명 문자열 수용, 2026-07-17 합의).
  - `userId` — **회원 BIGINT 필수**. 프로필 세션 버퍼 조회 키.
  - `reason` — 관측·진단용 선택 필드(처리 분기 미사용, 알려진 값 외 문자열도 400 아님). 알려진 값 `logout | inactivityTimeout` (`newConversation`은 **API 사유가 아니다** — 새 대화는 AI가 `threadId` 최초 등장으로 감지해 HTTP 없이 승격한다, 2026-07-31 확정).
- **게스트 생략**: 게스트는 프로필 대상이 아니므로 **Spring이 I-20 호출 자체를 생략**한다(`sub_type=guest`면 skip — 로그아웃·가입·로그인 승계의 게스트 세션 정리는 Redis만, FastAPI 맥락은 자체 TTL 소멸). 코드: `ChatSessionService#notifyIfMember`(로그아웃) · `#discardSessionsAsync`(게스트 승계 — 통지 없이 정리).
- **실발화 트리거(회원)**: **로그아웃(`logout`) 하나뿐**(노션 I-20 정본 2026-07-31 확정). 유휴 종료(`inactivityTimeout`)는 FastAPI 내부 idle flush, 새 대화는 **FastAPI가 `/chat`의 `threadId` 최초 등장으로 감지해 직전 thread 버퍼를 승격**(HTTP 신호 없음 — Spring은 thread를 모르고 티켓에도 싣지 않는다), 탭 종료(`tabClose`)는 계약에서 제외. Spring은 셋 다 통지하지 않으며 FastAPI는 수신을 전제하지 말 것.
- **승격 단위는 thread**: I-20 수신 시 FastAPI가 승격하는 대상은 그 세션에서 **아직 승격되지 않은 thread 버퍼**다 — 새 대화 전환·idle로 이미 승격된 thread는 재승격하지 않는다. thread 단위 자체 승격은 HTTP를 타지 않아 아래 멱등 키를 소비하지 않는다.
- **발화 조건의 한계(FastAPI가 알아야 할 것)**: ① 로그아웃 1회가 채널 수만큼 발화한다 — SHOPPING·CS·SELLER는 서로 다른 `sessionId`라 최대 3건, 멱등 키도 각각 별개. ② 채팅 세션 TTL(10분 sliding)이 지난 뒤 로그아웃하면 Spring에 세션이 없어 **통지 자체가 없다**. 즉 프로필 승격의 주 경로는 AI 자체 감지이고 I-20은 보조다.
- **멱등**: `dedupKey = "session-end:" + userId + ":" + sessionId`. 신규=`202 {"status":"accepted"}`, 중복=`202 {"status":"duplicate"}`. 재시도·중복 호출 무해.
- 구 계약 폐기: snake_case `session_id`/`user_id`/`guest_id`, `S-` 접두 정규식, checkpointer 삭제 부수효과, `200 {cleared}` 응답, `403 SESSION_FORBIDDEN`(노션 I-20 「제외된 구계약」).
- 구 "세션 만료 시 `DELETE {LLM_BASE_URL}/sessions/{id}` 통지(OPEN)" 항목을 대체 — 2026-07-17 확정.

## 3. 비기능 규약

| 항목 | 값 |
|---|---|
| 채팅 SSE 스트림 수명 | **FastAPI 소관**(직결) — 하트비트 `: ping` + 장비 idle 300s, 스트림 자체는 LLM 응답 1회 분량. Spring은 스트림을 붙들지 않음(03 §8) |
| Spring→FastAPI 타임아웃 | **P-5 추천**: 연결 2s/응답 3s(메인 렌더 블로킹 방지, 04 P-5). **세션 종료 통지(I-20)**: 짧게 — 멱등이라 실패해도 무해(FastAPI 자체 TTL이 백스톱). 채팅 60s는 직결이라 Spring 소관 아님 |
| FastAPI→BE 콜백 타임아웃 | 3s (I-1 후보조회·I-2 담기 등 — 콜백 실패 시 LLM은 해당 기능 없이 답변 지속) |
| FE→Spring 추천 목록 조회(CH-5) | 짧은 동기 조회(Redis 목록 + 카드 필드 부착) — 실패 시 FE가 카드 없이 텍스트만 우선 렌더 후 재시도. 티켓 만료 401은 CH-1b 재발급 → 1회 재시도(§1-0) |
| 재시도 | 자동 재시도 없음(중복 담기·중복 과금 방지). 실패는 사용자에게 노출하고 수동 재시도 |
| 게스트 제한 | 없음 — 횟수 제한 폐지(2026-07-07 회의). 게스트는 개인화 없이 응답 |
| 남용 방어(rate limit) | **FastAPI(LLM팀) 소유** — 세션/IP당 분당 N건 스로틀. **직결이라 FastAPI가 공개 진입점**이 됐으므로 여기가 1차 방어선(공개 문 경비 — 03 D5). 실 LLM 비용이 나가는 공격면. 기준치는 OPEN. 필요 시 Spring이 티켓 발급 앞단(CH-1)에 보조 스로틀 |
| 장애 시 | FastAPI 다운 → **직결이라 FE의 SSE 연결이 실패**하거나 FastAPI가 `error{LLM_UNAVAILABLE}` 발행 → FE가 안내 표시. 상품 조회·주문 등 비채팅(Spring)은 정상 동작(D-분산8) |

## 4. OPEN — LLM 팀 합의 필요 목록

**[2026-07-16 합의됨 — OPEN에서 내림]**
- [x] ~~SSE 직결 여부·인증~~ — **FE↔FastAPI 직결 + RS256/JWKS 단명 티켓 확정**(§1-0, 03 D5). AI팀 JWKS 검증 방식 채택 + 검증 대상을 단명 스트림 티켓으로.
- [x] ~~추천 카드 데이터 출처~~ — **`products` 이벤트는 `{productId, reason}`만, 카드는 FE가 P-7로 pull**(§1-2). 정형 진실은 Spring. *(2026-07-17 개정: `products` → `products.ready(listId)`, 카드 조회는 P-7 → CH-5 — §1-2)*
- [x] ~~추천 조회 흐름~~ — **2왕복(정형 후보조회 I-1 → 벡터 리랭킹 → Top5 → 카드 하이드레이션 P-7)** 확정(§1-2-1). *(2026-07-17: 마지막 단계에 I-21 콜백 저장 + CH-5 조회 추가 — §1-2-1)*
- [x] ~~세션 만료 통지 방식~~ — **I-20 `POST {LLM_BASE_URL}/events/session-end`로 확정(2026-07-17, §2-1)**. sessionId 형식도 **UUID로 합의 완료**(구 `S-` 접두 제약 폐기)

**[남은 OPEN]**
- [ ] **벡터DB 배치 동기화**(§1-2-2): 트리거·주기, 전체 재적재 vs 델타, 크롤링 파이프라인(상품 1만+) 연결 지점 — LLM팀 + 데이터 파이프라인 합의
- [ ] **라운드1 LIMIT·top-K 기준치**(§1-2-1): I-1 후보 상한(기본 50/최대 200 제안)과 LLM 투입 top-K(20~30 제안)의 실측 튜닝
- [ ] **프로필 추출 저장 시점** (세션 만료 시? 매 N턴?) — 기능 정의에도 미확정
- [ ] 카테고리 진입을 message 관성으로 갈지 전용 필드로 갈지
- [ ] P-5 개인화 추천(메인) API: `GET {LLM_BASE_URL}/recommendations?userId=` 형태 제안 — 응답이 상품 ID 목록이면 BE가 카드 데이터 조립(P-4 카드와 동형 — `purchaseState` 없음, 04 2026-07-28 결정. 구 이름은 `purchasable`). BE 측 타임아웃 연결 2s/응답 3s(04 P-5, 초과 시 인기 상품 fallback)
- [ ] 채팅 남용 방어(rate limit) 기준치 — 소유는 FastAPI로 확정(§3, 직결 공개 진입점), 수치만 OPEN
- [ ] 상세페이지 연관 추천 2종(함께 구매/대체)의 소스: LLM 생성 vs BE 규칙 기반(같은 카테고리 인기순) — MVP는 BE 규칙 기반 제안
- [ ] **confirm 전송 형식**(§1-3): 전용 필드 `{action:"confirm", draftId}` vs 특수 메시지 — LLM 확정 대기 (draft 이벤트 필드 자체는 §1-3으로 확정)
- [x] ~~I-21/CH-5 추천 목록 스키마~~ — **확정(2026-07-18 LLM 합의, §I-21)**: listId FastAPI 생성, reasons 콜백 포함(카드용) → CH-5 echo, SSE 이유는 채팅용으로 이원화
- [x] ~~**I-13 행동 이벤트 조회/집계**(`GET /internal/seller/{brandId}/events`) 본문 명세~~ — **노션 확정·구현 완료(2026-07-18)**, 상세 §I-6b
- [x] ~~I-20 sessionId 형식~~ — **UUID로 합의 완료(2026-07-17 LLM 팀 확인, §2-1)**
- [ ] **CH-3(CS 챗봇) 폐지/유지**: 직결 전환 후 문의 챗봇 존치 여부 — LLM 확인 중(04 §6)
- [ ] **SELLER 툴 바인딩**: `brandId`(및 신원 필드 전부)는 LLM 툴 파라미터로 노출 금지 — **티켓 claim/세션 컨텍스트에서 코드 주입**(§1-0). 인젝션으로 타 브랜드 id를 넣는 경로 차단
- [ ] **SELLER 프롬프트 가드레일**: ① "직접 반영했어요" 류 발화 금지(쓰기 툴이 없는데 성공 환각 시 판매자가 적용 버튼을 안 누름) ② 직접 반영 요구엔 "확인 후 적용 버튼으로 즉시 반영" 안내
