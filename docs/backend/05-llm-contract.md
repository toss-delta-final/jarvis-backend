# 05. LLM(FastAPI) 연동 계약 — v0.5 (2026-07-22 · I-20 정본 정합)

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
  "iss": "jarvis-spring-auth",
  "aud": "jarvis-fastapi-ai",
  "scope": "chat:stream",       // SELLER도 동일 scope — 판매자는 role/brandId claim 추가(아래·§I-6b)
  "exp": 1720000000             // 발급 후 30~60초
}
```
- **신원은 티켓에서, body가 아님**: `userId`/`guestId`/`brandId`는 FastAPI 툴 인자·요청 body로 받지 않고 **티켓 `sub`/claim에서** 취한다(클라이언트 주장 무시 — 인젝션 차단). `/internal` 콜백엔 이 값을 **메아리**로 실어보낸다(§0-1).
- **왜 전권 AT 직접 안 씀**: `EventSource`(GET)는 커스텀 헤더가 안 실려 AT가 쿼리스트링 노출 → 30~60초 read-only 티켓만 내보내 유출 피해를 "스트림 1회"로 봉쇄. 게스트도 동일 경로로 발급(`sub_type:guest`).
- **연결 시점 인증**: 티켓이 만료돼도 이미 열린 스트림은 유지(SSE 관례 — 스트림 자체가 LLM 응답 1회 분량).

### 1-1. 추천 챗봇 `POST {LLM_SSE_URL}/chat` (FE 직결, `Authorization: Bearer <티켓>`)

```json
{
  "sessionId": "uuid",          // Spring이 발급·TTL 관리(10분 sliding). 새 sessionId = 새 대화
  "channel": "SHOPPING",         // SHOPPING | CS | SELLER
  "message": "유럽여행 가는데 필요한 거 추천해줘"
}
```

- **신원(userId/guestId)은 body에 없다 — 티켓 claim(`sub`/`sub_type`)에서** 취한다(§1-0). 게스트면 `sub_type:guest`, 개인화 없이 응답.
- 멀티턴 맥락은 sessionId 기준으로 **FastAPI가 인메모리/자체 스토어에 유지** (BE는 메시지를 저장하지 않음). 세션 종료 시 Spring이 **I-20 `POST {LLM_BASE_URL}/events/session-end`** 로 정리 통지(§2-1) — 트리거: 로그아웃 / 새 대화(CH-1 재호출) / 가입·로그인 게스트 승계(노션 A-1/A-2 07-20). 유휴·탭 종료는 통지 없이 TTL 소멸(FastAPI 자체 TTL 백스톱 §3 — enum의 IDLE_TIMEOUT·TAB_CLOSE는 예약). *(구 `DELETE {LLM_BASE_URL}/sessions/{id}` 안(OPEN이었음)을 대체 — 2026-07-17 확정. sessionId 형식도 UUID로 합의 완료)*
- 카테고리 진입(메인에서 카테고리 클릭)은 별도 필드 없이 message로 전달: FE가 `"[카테고리] 주방용품 보여줘"` 형태로 첫 메시지 구성. **(OPEN: 전용 필드로 분리할지)**

### 1-2. 응답: SSE 스트림

이벤트 타입 9종. **FastAPI가 FE로 직접 발행**한다(직결 — Spring 패스스루 아님). `products.ready` 이벤트는 **카드 페이로드 없음 — `listId` 상관키만** 싣고, 카드는 FE가 CH-5(`GET /api/chat/lists/{listId}`)로 Spring에서 조회한다(§1-2-1).

```
event: token          data: {"text": "유럽여행이라면 "}       // 답변 텍스트 조각 (스트리밍)
event: conditions     data: {"items": ["기념일", "호텔 레스토랑", "우아한", "원피스"]}
                                                             // LLM이 발화에서 추출한 조건 — FE가 결과 상단에 제거 가능한 칩으로 표시
event: suggestions    data: {"items": [{"relaxation": "가격 상한을 5만원으로 완화", "estCount": 12}]}
                                                             // 제안 칩 — 조건 완화 제안 + 완화 시 예상 결과 수
event: budget         data: {"totalBudget": 100000, "verifiedSum": 87000, "withinBudget": true, "droppedItems": []}
                                                             // 총액 예산 검증 — verifiedSum은 Spring 검증가 합
event: products.ready data: {"listId": "uuid"}               // 추천 목록 준비 완료 — 카드 페이로드 없음(listId 상관키만)
                                                             // I-21 콜백으로 Spring 저장 성공 후에만 발행 — FE가 CH-5로 카드 조회
event: action         data: {"type": "CART_ADDED", "message": "무선 키보드 1개를 장바구니에 담았어요", "cartItemId": 55}
event: draft          data: {"draftId": "d-1", "op": "update", "productId": 3, "changes": [{"field": "price", "before": "12000", "after": "9900"}]}
                                                             // SELLER 전용 — 수정 초안. FE가 diff 카드 + [적용] 버튼 렌더 (§1-3)

event: done           data: {"finishReason": "stop"}         // stop | zero_result
event: error          data: {"code": "LLM_TIMEOUT", "message": "잠시 후 다시 시도해주세요"}
```

- `conditions`: 디자인 시안의 "조건 칩" UI 지원. **칩 X 제거 시 FE는 후속 메시지로 전달** — `message: "[조건 제거] 우아한"` 형태의 규약 문자열(같은 세션이라 LLM이 맥락 유지, 재추천 후 갱신된 conditions·products.ready 재발행). 별도 API 없음.
- `suggestions`: 결과가 없거나 적을 때의 **조건 완화 제안 칩** — `relaxation`(제안 문구)·`estCount`(완화 시 예상 결과 수). 칩 클릭 시 FE가 후속 메시지로 전달(조건 제거 칩과 같은 관성).
- `budget`: "예산 10만원으로 캠핑 세트" 류 총액 시나리오 — `totalBudget`(사용자 예산)·`verifiedSum`(Spring 정형 진실 가격 합)·`withinBudget`·`droppedItems`(예산 초과로 제외된 항목).
- `products.ready`: 구 `products{productId, reason}` 이벤트를 **대체**(2026-07-17) — 추천 ID·이유·카드를 SSE에 싣지 않는다. FastAPI가 **I-21 콜백으로 Spring에 목록 저장 후** `listId`만 발행하고, FE는 CH-5로 카드 완결 필드(가격·정가·이미지·rating·reviewCount)를 순서 보존으로 받는다. reason은 이원화 확정(2026-07-18): SSE = 채팅 말풍선용, I-21 콜백 `reasons` = 추천 카드용(CH-5 echo — §I-21).
- **카드 필드의 출처는 BE**: 표시 데이터는 **LLM(SSE)이 아니라 FE가 CH-5로 Spring에서** 받는다. 이유: (a) 가격·재고 같은 **정형 진실은 Spring/MariaDB가 원천**(벡터DB는 배치라 낡을 수 있음), (b) LLM은 "누굴·왜"만 결정하고 표시 데이터는 BE 소유. `productId`는 BE 상품 ID이자 **벡터DB 공유 키** — 카드의 상세 이동은 FE가 `/products/{id}`로. 찜 버튼은 FE가 M-5 직접 호출 — LLM 무관.
- `action`: 담기 등 부수효과의 결과 통지. 실패 시 `type: "CART_ADD_FAILED"` + `reason` 3종 `OUT_OF_STOCK | PRODUCT_NOT_FOUND | CART_ERROR` — 게스트 담기는 허용이라(I-2, 02 D30) `GUEST_NOT_ALLOWED`는 없다.
- `done.finishReason`: `stop`(정상 종료) \| `zero_result`(조건 만족 결과 0건 — FE는 suggestions 칩 중심으로 렌더).

### 1-2-1. 추천 데이터 흐름 — 2왕복 리랭킹 (노션 「추천 에이전트 흐름」, 03 §7 ③)

DB가 둘(커머스 MariaDB=Spring / 벡터=FastAPI)이라 조회가 둘로 갈린다:

```
1. FE → FastAPI(SSE)   : 티켓으로 연결, 질문 전달
2. FastAPI             : 정형조건(가격·카테고리·색상·재고·상태) + 의미조건(원룸 적합…) 추출
3. FastAPI → Spring    : [1왕복] 정형조건만 → GET /internal/products/search (I-1)
   Spring → FastAPI    :   MariaDB 후보 조회 — 정형 진실 확정, 리랭킹용 최소필드만 반환
4. FastAPI             : 벡터DB(임베딩)로 의미 리랭킹 → top-K만 LLM 태워 Top5 선정 + 이유·응답 생성
5. FastAPI → Spring    : [콜백] 확정 Top5 목록 저장 → POST /internal/recommendations (I-21)
   Spring              :   {sessionId, listId, productIds[](순서 유지)} Redis TTL 저장
6. FastAPI → FE(SSE)   : token/conditions/…/products.ready{listId}/done — 콜백(5) 성공 후에만 발행
7. FE → Spring         : [2왕복] products.ready 수신 트리거 → GET /api/chat/lists/{listId} (CH-5)
   Spring → FE         :   저장 순서대로 카드 완결 필드(가격·정가·이미지·rating·reviewCount) 부착해 반환 → FE 렌더
```

- **콜백 실패 시 products.ready 발행 금지** — FE가 존재하지 않는 listId를 조회하는 경로를 만들지 않는다. I-21 body·CH-5 응답 스키마는 **OPEN(LLM 협의 중)**.

- **비용 상한 2개**: (a) **라운드1 LIMIT** — 정형조건이 느슨(대분류만)하면 후보가 폭발하므로 I-1이 최대 N개만(기본/최대 §I-1). (b) **top-K 캡** — 벡터 리랭킹 후 LLM에 태우는 후보를 20~30개로 제한(토큰은 벡터검색이 아니라 LLM이 후보를 읽을 때 든다).
- **Top5 넉넉히 선정**: 카드 부착(CH-5)에서 HIDDEN·품절이 드롭될 수 있으니 FastAPI는 5개보다 넉넉히(예 7~8) 골라 순서를 준다 — 드롭 후에도 5개 유지.
- **정합성 경계**: 벡터DB attributes는 **배치 동기화**(§1-2-2)라 낡아도 무방 — 정형 진실(가격·재고·상태)은 3(라운드1)·7(CH-5) 모두 Spring이 확정하므로 거짓 가격·품절이 카드에 안 뜬다.

### 1-2-2. 벡터DB 동기화 (LLM 팀 소유)

- **벡터DB 구성**: `productId`(커머스 DB 공유 키) · `attributes` · `embedding`. 임베딩·의미검색은 FastAPI 소유·직접 접근(03 D-분산9, D7 예외).
- **동기화 방식**: 상품 신규/수정(크롤링 적재·챗봇 수정 I-11 포함)은 **매번 실시간 락 대신 배치로 임베딩 재생성·upsert**(비용 큼 — 크롤링 상품 1만+). 변경분 수집은 **I-17(`GET /internal/products/changes` — since 커서+limit) 배치 pull**(§I-17). 정합성이 치명적이지 않은 이유는 위 "정합성 경계".
- **(OPEN — 협의 중)**: 배치 트리거·주기, I-17 커서 방식·attributes 스키마·리뷰 포함 여부, 크롤링 파이프라인과의 연결 지점 — **LLM 팀 + 데이터 파이프라인 합의 필요**.

### 1-3. CS/판매자 챗봇

- 같은 `/chat` 엔드포인트에 `channel: "CS"` / `"SELLER"`. 분기는 FastAPI 내부(프롬프트·툴셋 차이).
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

### I-1. 상품 검색 (추천 1왕복 · 후보 조회) `GET /internal/products/search`
- **역할**: 추천 2왕복 중 **라운드1** — 정형조건으로 MariaDB 후보를 좁혀 **리랭킹용 최소필드**만 반환(1-2-1). 표시 데이터는 안 준다(CH-5 카드 부착 담당).
- query: `keyword?`(상품명+summary+attributes LIKE), `categoryName?`, `minPrice?`, `maxPrice?`, `brandName?`, `color?`/기타 정형 속성?, `size`(**라운드1 LIMIT — 기본 50 / 최대 200**, 후보 폭발 방지). **정형 진실(가격 범위·재고·판매상태 필터)은 Spring SQL에서 적용** — 살 수 없는 상품은 후보에서 제외.
- `categoryName` 해석(02 D20 — 2단 계층): **대분류명이면 하위 소분류 전체를 포함해 검색**, 소분류명이면 해당 소분류만. 메인 해시태그가 대분류(#패션)라 LLM이 대분류명을 보내는 게 기본 경로 — 대분류 지정이 0건이 되는 일이 없어야 한다
- 응답 item (**리랭킹용 최소**): `productId, name, summary, attributes(JSON), categoryName, brandName`. ⚠️ **display 필드(`price·originalPrice·imageUrl·rating·reviewCount·options`)는 제거 — 카드 조회(CH-5 — 스키마 확정 전까지 P-7 유지)로 이동.** FastAPI는 여기서 받은 `productId`로 자기 벡터DB의 embedding을 찾아 의미 리랭킹한다.
- attributes까지 반환하는 이유: LLM이 "린넨 소재만" 같은 세밀 조건을 후처리 필터링할 수 있게(서버는 후보만 좁힘 — 02 D7). 카테고리별 속성 축의 정의는 `category.attribute_schema`(02 D11) — 시드 데이터·벡터DB attributes·LLM 프롬프트가 같은 축을 공유한다.

### I-2. 장바구니 담기 `POST /internal/cart/items`
- body: `{ "userId": 123, "guestId": null, "productId": 1, "optionId": null, "quantity": 1 }` — userId/guestId 중 하나(채팅 요청의 메아리). quantity 1~99 (04 §3과 동일 검증: 입구가 달라도 같은 CartService)
- **게스트(userId null)도 guestId로 담기 성공** (02 D30 — 2026-07-10 개정, 기존 403 유도 폐기). 로그인 유도는 결제 시점의 FE 몫 — LLM은 "장바구니에 담았어요. 주문하실 땐 로그인이 필요해요" 정도로만 안내.
- 옵션 필요한데 optionId 없으면 400 `CART_OPTION_REQUIRED` + options 목록 반환 → LLM이 "어떤 색상으로 담을까요?"로 되물음. **(2026-07-18 구현 확정)** options는 envelope `error.detail.options[{optionId, name, extraPrice}]`로 실린다.
- 성공 응답에 cartItemId — action 이벤트에 사용. 행동 이벤트(behavior_events `add_to_cart`)는 서버 적재가 아니라 FE 배치 소관(04 §8 E-1 — 2026-07-17 전환).

### I-3. 인기 상품 `GET /internal/products/popular?size=12`
- 무관 질문 시 카드 영역 유지용. 응답 형식 I-1과 동일.

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
- **I-12** 삭제 → `status=HIDDEN` 전환 + STATUS 로그. **이미 HIDDEN이면 409 ALREADY_HIDDEN** (노션 계약 기준, 2026-07-18 — FastAPI는 409를 이미-처리됨으로 해석).
- **I-13** `…/events?from&to(필수)&eventType?(콤마 복수)&productId?&groupBy=product|eventType|date` → groupBy별 응답: `product`면 `{groupBy, rows[{productId, productName, counts{productView, addToCart, checkoutStart, purchaseComplete}, viewToCartRate, uniqueVisitors}], total}`, `eventType`이면 `{groupBy, counts{...}}`, `date`면 `{groupBy, series[{date, productView, addToCart, purchaseComplete}]}`. **노션 본문 확정(2026-07-18)** — 이 항목만 BE 확정이 아니라 노션 정본. 집계 규칙: ① 판매자 스코프 = `behavior_events.product_id → product → brand.seller_id`이므로 **product_id 없는 이벤트(session_start/login/search/page_view)는 브랜드 귀속 불가라 제외**(전역 행동은 I-8) ② counts 키는 event_type의 camelCase ③ `uniqueVisitors` = distinct(member_id, guest_id) 게스트 포함 ④ `viewToCartRate` = addToCart/productView, 분모 0이면 null ⑤ 중복 제거 불필요(`client_event_id` UNIQUE로 배제 — E-1 서버 처리 ④). **`purchaseComplete`는 이벤트 기준(주문완료 페이지 발사)이라 매출·주문수의 권위가 아니다 — 정확한 수치는 I-6/I-14(order 기준)**. 실패: 400 `INVALID_PERIOD`(from>to·형식 오류) / 400 `INVALID_GROUP_BY`(groupBy·eventType 값 오류).
- **I-14** `…/order-events?toStatus?(콤마 복수)&actorType?&from&to&stats?&groupBy=memberId?&limit` → `{brandId, from, to, items[{orderId, fromStatus, toStatus, actorType, reason, createdAt}], statusCounts?(stats=true), memberCounts?[{memberId, count}](groupBy=memberId)}` — 브랜드 스코프 = 자사 아이템 포함 주문.
- **I-15** `…/product-changes?changeType?(PRICE|STOCK|STATUS)&productId?&from&to&limit` → `{brandId, from, to, items[{productId, changeType, oldValue, newValue, createdAt}]}` 최신순.
- **I-16** `…/churn?from&to(필수)&inactiveDays?(기본 30)` → `{inactiveDays, cohortSize, churnRate, preChurnSignals{cancelCount, returnReasonsTop[{reason,count}], zeroResultSearchSessions, priceIncreaseExposed}, members[{memberId, lastActivityAt, lastLoginAt, sessions30d, preChurnEvent}]}` (**노션 I-16 본문 정본**) — 코호트 = 기간 내 자사 상품 상호작용 회원 중 최근 inactiveDays일 behavior_events 무활동, "마지막 로그인" = account_event_logs.LOGIN_SUCCESS 단일 출처(서버 내부 조인), priceIncreaseExposed = product_change_logs(PRICE 인상) 조인·탈퇴자 제외 없음(MVP). 실패 400 `INVALID_PERIOD`.
- **S-4 티켓**: 기존 claim(§1-0)에 `role:"seller"`, `brandId:<long>` 추가(**노션 CH-6 확정 2026-07-18** — 구 표기 `channel:"SELLER"`/`brand_id`는 폐기, scope는 `chat:stream` 유지). CH-1(`/api/chat/sessions`)로는 SELLER 채널 발급 불가(400) — 입구는 S-4뿐. CH-1b 재발급도 세션에 보관된 brandId로 SELLER 티켓 유지.

### I-9. 자사 상품 목록 `GET /internal/seller/{brandId}/products` *(구 I-7 판매자 상품 상세를 흡수)* + 쓰기 I-10/I-11/I-12
- 응답 item: `productId, name, summary, attributes(JSON), description, price, originalPrice, status, stockQuantity, displayedSalesCount(base_sales_count + order_item 집계)` — query: status/q/limit/offset. 수정 초안(draft) 생성의 읽기 소스(I-1은 추천 리랭킹용으로 슬림하므로 여기서 명시).
- `product.brand_id ≠ brandId`면 **404 `PRODUCT_NOT_FOUND`**(미존재와 동일 — 존재 은닉, 노션 I-11) — 소유권 검증을 internal에서도 반복(productId는 LLM이 채우는 값이라 신뢰 불가). **쓰기(I-10 등록 / I-11 수정 / I-12 삭제)는 HITL confirm 후에만 호출**(§1-3): I-11은 바뀐 필드마다 product_change_logs 기록(동일값 미기록, 응답 changes[]), I-10 등록은 change_logs 미기록, I-12는 soft delete(HIDDEN) 전용.
- `brandId`는 LLM 툴 인자가 아니라 **FastAPI가 티켓 claim/세션 컨텍스트에서 코드로 주입**해야 한다(§4 합의 항목).

### I-17. 상품 정보 배치 pull `GET /internal/products/changes?since=&limit=` (벡터DB 동기화 — §1-2-2)
- FastAPI가 주기 배치로 변경분 pull: `since` 커서 + `limit`, `items[].status`는 `ACTIVE|DELISTED`, 초기 전체 구축은 `since="0"`. 커서 방식·attributes 스키마·리뷰 포함 여부 — **OPEN(LLM 협의 중)**.

### I-18. 챗봇 장바구니 조회 `GET /internal/cart`
- userId/guestId 메아리(게스트 허용). 응답 item에 `productName·optionName` 필수(LLM이 그대로 발화), 빈 장바구니도 200(빈 배열).

### I-19. 구매 이력 목록 `GET /internal/members/{userId}/orders`
- CS 챗봇 "내 주문 어때?" 용 — `status` 단일 필터(어휘: `ORDERED|SHIPPING|DELIVERED|CONFIRMED|CANCELLED|RETURNED` — 우리 상태명). 응답은 camelCase·숫자 id, 아이템에 `categoryName`(소분류명 — 노션 I-19), `shippingFee` 항상 0(배송비 없음 확정). I-4(요약)와 역할 분담.

### I-21. 추천 목록 콜백 `POST /internal/recommendations` (확정 2026-07-18 — LLM 합의)
- body: `{ "sessionId": "<uuid>", "listId": "<FastAPI 생성 문자열>", "productIds": [ … ], "reasons": [ { "productId", "reason" } ]? }`(순서 유지) — Spring이 Redis TTL 저장, FE가 CH-5(`GET /api/chat/lists/{listId}`)로 조회. **products.ready 발행 전 호출 — 콜백 실패 시 products.ready 발행 금지**(§1-2-1).
- **추천 이유 이원화(합의)**: SSE는 채팅 말풍선용(Spring 무관), 콜백 `reasons`는 우측 추천 카드용 — CH-5 카드에 `reason`으로 echo(없으면 null).
- 검증: sessionId UUID / listId 영숫자·`-`·`_` ≤64(그 외 400 — Redis 키 안전) / productIds 1~20개. Redis TTL 10분(세션 TTL과 동일). CH-5 응답은 `{listId, items[카드 완결 필드 + reason]}`(순서 보존, HIDDEN·품절 드롭, 만료 404).
- **listId 엔트로피(2026-07-18 시큐리티 리뷰)**: CH-5는 게스트 허용 공개 조회라 listId가 사실상 bearer 키다 — FastAPI는 listId를 **UUID급 무작위(≥128bit)**로 생성해야 한다(순번·타임스탬프 등 추측 가능한 형식 금지).

## 2-1. 아웃바운드: Spring → FastAPI

### I-20. 세션 종료 통지 `POST {LLM_BASE_URL}/events/session-end` — **방향 예외(Spring→FastAPI)**

세션 종료를 통지해 FastAPI의 **회원 프로필 버퍼(승격 전 발화)를 프로필로 조기 승격**하는 트리거. best-effort·멱등이며 응답은 **202**(노션 I-20 정본 2026-07-21 개정 반영).

- **인증**: `X-Internal-Token` 필수 — 인바운드 콜백과 동일한 공유 시크릿(§0 ②, `app.internal.token`). 미검증 시 401 `INTERNAL_TOKEN_INVALID`.
- **body** (camelCase): `{ "sessionId": "<uuid>", "userId": <회원 BIGINT>, "reason": "logout" }`
  - `sessionId` — Spring이 UUID로 발급(정규식 제한 없이 불투명 문자열 수용, 2026-07-17 합의).
  - `userId` — **회원 BIGINT 필수**. 프로필 세션 버퍼 조회 키.
  - `reason` — 관측·진단용 선택 필드(처리 분기 미사용, 알려진 값 외 문자열도 400 아님). 알려진 값 `logout | tabClose | inactivityTimeout | newConversation`.
- **게스트 생략**: 게스트는 프로필 대상이 아니므로 **Spring이 I-20 호출 자체를 생략**한다(`sub_type=guest`면 skip — 로그아웃/새 대화/가입·로그인 승계의 게스트 세션 종료는 Redis 정리만, FastAPI 맥락은 자체 TTL 소멸). 코드: `ChatSessionService#notifyIfMember`.
- **실발화 트리거(회원)**: 로그아웃(`logout`) / 새 대화=CH-1 재호출(`newConversation`). 유휴·탭 종료(`inactivityTimeout`·`tabClose`)는 예약 — 통지 없이 TTL 소멸, FastAPI는 수신을 전제하지 말 것.
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
- [ ] P-5 개인화 추천(메인) API: `GET {LLM_BASE_URL}/recommendations?userId=` 형태 제안 — 응답이 상품 ID 목록이면 BE가 카드 데이터 조립(P-7과 동형). BE 측 타임아웃 연결 2s/응답 3s(04 P-5, 초과 시 인기 상품 fallback)
- [ ] 채팅 남용 방어(rate limit) 기준치 — 소유는 FastAPI로 확정(§3, 직결 공개 진입점), 수치만 OPEN
- [ ] 상세페이지 연관 추천 2종(함께 구매/대체)의 소스: LLM 생성 vs BE 규칙 기반(같은 카테고리 인기순) — MVP는 BE 규칙 기반 제안
- [ ] **confirm 전송 형식**(§1-3): 전용 필드 `{action:"confirm", draftId}` vs 특수 메시지 — LLM 확정 대기 (draft 이벤트 필드 자체는 §1-3으로 확정)
- [x] ~~I-21/CH-5 추천 목록 스키마~~ — **확정(2026-07-18 LLM 합의, §I-21)**: listId FastAPI 생성, reasons 콜백 포함(카드용) → CH-5 echo, SSE 이유는 채팅용으로 이원화
- [x] ~~**I-13 행동 이벤트 조회/집계**(`GET /internal/seller/{brandId}/events`) 본문 명세~~ — **노션 확정·구현 완료(2026-07-18)**, 상세 §I-6b
- [x] ~~I-20 sessionId 형식~~ — **UUID로 합의 완료(2026-07-17 LLM 팀 확인, §2-1)**
- [ ] **CH-3(CS 챗봇) 폐지/유지**: 직결 전환 후 문의 챗봇 존치 여부 — LLM 확인 중(04 §6)
- [ ] **SELLER 툴 바인딩**: `brandId`(및 신원 필드 전부)는 LLM 툴 파라미터로 노출 금지 — **티켓 claim/세션 컨텍스트에서 코드 주입**(§1-0). 인젝션으로 타 브랜드 id를 넣는 경로 차단
- [ ] **SELLER 프롬프트 가드레일**: ① "직접 반영했어요" 류 발화 금지(쓰기 툴이 없는데 성공 환각 시 판매자가 적용 버튼을 안 누름) ② 직접 반영 요구엔 "확인 후 적용 버튼으로 즉시 반영" 안내
