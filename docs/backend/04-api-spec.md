# 04. REST API 명세

> 기준: 「기능 정의 - 이소희」의 페이지별 기능에서 역산. 응답은 전부 03 문서의 envelope(`{success, data|error}`), 인증 규약도 03을 따른다.
> 표기: 🔓 인증 불필요 / 🔑 로그인 필요 / 🏪 SELLER / 🛡 ADMIN / ⚙ internal(서비스 토큰). `{}`는 path variable.
> LLM 콜백(⚙ `/internal/*`)과 채팅 직결(SSE·티켓)의 상세 스키마는 [05 LLM 연동 계약](05-llm-contract.md)이 원본이고 여기서는 목록만 둔다.
> **2026-08-05 `purchasable` → `purchaseState` 교체(FE 요청 08/04)**: boolean 하나로는 품절과 숨김을 구분할 수 없어 같은 `false`가 화면마다 다른 문구로 나갔다(찜은 "판매 중지", 브랜드 목록은 "품절"). `AVAILABLE`/`SOLD_OUT`/`HIDDEN` 3값으로 바꾸고 **겹치면 HIDDEN 우선**(돌아오지 않는 상품이라 재입고 안내를 하면 안 된다). 파생은 `PurchaseState` 한 곳 — O-1의 `unavailableItems[].reason`도 같은 어휘를 쓴다. 대상 C-1·M-4·M-7·P-2·P-6·I-18(+I-28 초안). **CH-5는 대체 없이 제거** — 못 사는 상품을 드롭하므로 값이 항상 AVAILABLE이라 P-4·P-5와 같은 이유로 뺀다. **필드 추가가 아니라 교체라 BE·FE·LLM 동시 배포가 필요하다.**
> **2026-07-28 P-4 품절 제외 + 카드 `purchasable` 제거(노션 📡 API 명세서 P-4 댓글 확정)**: 인기 상품은 **품절(`stock_quantity = 0`)을 집계 3단계 전부에서 제외**한다 — 메인에 노출되는 상품은 살 수 있어야 한다. 살 수 없는 상품이 애초에 목록에 없으므로 응답 카드에서 **`purchasable`을 뺀다**(값이 항상 true라 의미 없음, P-5는 이미 없던 필드). P-4 전용 `PopularCardResponse`를 두고 공용 `ProductCardResponse`(P-6·M-4·M-7·CH-5)는 그대로 — 그쪽은 HIDDEN·품절을 목록에 유지하거나(개인 목록) 재고와 무관하게 표시하므로 필드가 계속 필요하다. `popularIds()`를 공유하는 **I-3(내부 인기 후보)도 같이 품절 제외** — I-1이 이미 `stockQuantity > 0`로 거르므로 오히려 정합해진다.
> **2026-07-28 P-7 폐기(팀 결정)**: 추천 카드 하이드레이션 **P-7 `GET /api/products/cards?ids=`는 폐지** — CH-5(`GET /api/chat/lists/{listId}`) 확정·구현으로 대체 완료, FE 전환도 끝나 실사용처가 없다(노션 「📡 API 명세서」에서도 행 삭제됨). "범용 다건 카드 조회로 유지" 문구도 함께 폐기 — 필요해지면 새 번호로 다시 정의한다. 구현 제거: `ProductController.cards`·`ProductService.getPublicCards`. 카드 조립 공용 로직(`getCardsByIds`)은 P-4·P-6·M-4·M-7·CH-5가 계속 사용.
> **2026-07-21 S-5 폐기(팀 결정)**: 판매자 상품 **직접 수정(S-5 `PATCH /api/seller/products/{id}`)은 미채택** — 상품 수정은 챗봇 경로(I-11, HITL confirm)만. 판매자 직접 경로는 조회(S-1 대시보드·S-2 주문·S-3 상품목록)만 남긴다(백엔드→프론트 정보 표시). 이전 "S-5·I-11 병존 확정(07-17)"은 이 결정으로 폐기. 아래 표·05 §1-3의 병존 문구도 정정.

> **2026-07-18 응답 스키마 정합화(팀 결정: 노션 「📡 API 명세서」가 응답 예시의 기준)** — FE 파싱을 깨는 차이를 노션에 맞춰 일괄 수정:
> 목록 래핑(P-1 `categories`, P-4·M-7·M-4 `items`, P-3·O-3·O-6·M-9 `content`, M-8a `addresses`), 필드명(P-2 옵션 `optionId`, P-3 `reviewId`·`authorNickname`, M-1 `reviewId`, M-8 `addressId`, C-1 아이템 `name`, O-3 `representativeStatus`, 채팅 `ticketTtlSeconds`+`ttlSeconds` 추가), 구조(P-6 `brand` 중첩, O-4 `address` 중첩, M-9 `answer` 중첩·명시적 null), 동작(M-3 `reportId`·M-5 `productId` 반환, P-3 없는 상품 404, I-4 한국어 상태문구·없는 회원 404, I-18 `CART_QUERY_INVALID`, I-19 `ORDER_INVALID_PARAM`·`itemsTotal`, CH-1 바디 생략 시 SHOPPING, C-2/I-2 담기 합산 99 초과 400 — 로그인 병합 클램프(02 D30)는 유지). C-1(공개)·I-18(internal) 아이템명은 노션대로 `name`/`productName`으로 분리.
> **유지(노션 쪽 수정 완료)**: 401 2종 분리(03 D2), 소유권 위반 404(IDOR — 공개 API), `+09:00` 오프셋(03 D2), I-1/I-3 최소필드(05 7-17 재설계 — 노션 페이지를 05 기준으로 갱신), 카드 `purchasable` 등 추가 필드.
> **Phase 2(판매자 분석·상품 쓰기, 같은 날)**: I-6~I-16·S-1~S-3·S-5도 노션 기준으로 정합화 — from/to 필수+`INVALID_PERIOD`, 422 어휘(`MISSING_FIELD`/`INVALID_PRICE`/`INVALID_STOCK`), I-12 재삭제 409 `ALREADY_HIDDEN`(05 §1-3 멱등 규정 폐기), I-13 구현(501 스텁 해소), I-8/I-14 어뷰징 지표·I-16 코호트 재설계, I-10/I-11/S-5 `attributes` JSON 객체 수용, internal 상품 쓰기 소유권 위반은 404(공개 S-5는 403 유지), CH-6 티켓 클레임 `role`/`brandId`(노션 기준). 노션 쪽은 판매자 페이지 401 코드를 `INTERNAL_TOKEN_INVALID`로 통일하고 I-14 상태 어휘를 01 기준으로 정정.
> **Round 5(노션 07-20/07-21 개정 정합, 2026-07-21)**: A-1/A-2 게스트 승계를 body `guestId` → **guest_id 쿠키 기준**으로(보내도 무시), 백필은 가입 전용·로그인은 병합만, 가입/로그인 성공 시 게스트 채팅 세션 정리(Redis만 — 게스트는 I-20 미발화). E-1 익명 요청에서 쿠키 없으면 게스트 발급(주체 없는 행 방지). I-19 아이템에 `categoryName` 추가. O-4 `paidAt`·O-6 `processedAt` 등 미확정 값은 키 생략이 아니라 **명시적 null**(NON_NULL 제거 — P-3 `distribution`의 page≥1 생략은 유지).
> **Round 4(멱등성·시크릿·성능, 2026-07-20)**: 성공 응답의 `data`를 **항상 직렬화**(값 없으면 `"data": null` — A-3·C-4·M-6·M-8d. 실패 응답은 종전대로 `data` 키 없음, `ApiResponseSerializer`). `JWT_SECRET`을 환경변수로 분리(하드코딩 제거). 기본 배송지 단일성을 DB가 강제(`uk_address_default` 생성컬럼 유니크) + 해제를 조건부 UPDATE로 — 경합 시 영구 500이던 것을 409로. E-1 배치 적재는 UNIQUE 경합 시 건별 저장으로 폴백(중복 1건이 배치 전체를 날리던 문제). I-7/I-13 `checkout_start` 브랜드 귀속을 `JSON_OVERLAPS`로 SQL화(전 브랜드 이벤트 앱 로드 제거).
> **Round 3(전체 검토 후속, 2026-07-18)**: E-1 응답을 노션대로 202 **본문 없음**으로(envelope 제거), I-6 summary `statusCounts`를 노션 확정 어휘로(주문 단위 `PAID`/`PAYMENT_FAILED` + 아이템 단위 `CANCELLED`/`RETURNED`, 4키 고정·0 채움), S-2/S-3 `page`/`size`·I-9 `limit`/`offset` 검증 추가(범위 밖 400 — 기존 500 제거), 로그아웃의 채팅 세션 정리를 Redis 장애로부터 격리(실패해도 로그아웃 성공, TTL 소멸 위임). 노션 쪽 정정: I-7 v1 `computable:false` 규정 폐기, I-10 `categoryId` 필수(소분류만), I-18 응답 superset 기재, I-21 listId 엔트로피 요구(UUID급 ≥128bit), M-8b/8c 응답 전체 주소 객체 기재.

## 1. auth

| # | Method | 경로 | 인증 | 설명 |
|---|---|---|---|---|
| A-1 | POST | /api/auth/signup | 🔓 | 회원가입. body: email, password, nickname, gender, birthDate, agreeTerms(true 필수), agreePrivacy(true 필수) — 성공 시 자동 로그인(토큰 발급) + 게스트 구간 승계(장바구니 병합 + 구간 귀속 기록 `converted_member_id`·`converted_at` + 쿠키 반납 — 02 D5·D30). **behavior_events 백필은 폐기**(2026-07-31 — 로그는 고쳐 쓰지 않고 귀속 기록으로 해석). **승계 신원은 guest_id HttpOnly 쿠키에서 서버가 취함**(노션 07-20 개정 — body guestId 폐기, 보내도 무시). 부수효과: 게스트 채팅 세션은 **건드리지 않는다**(2026-07-31 — 지우면 뒤이어 오는 CH-7이 승계할 대상을 잃는다). 승계 안 하면 주인 없이 남아 TTL 10분으로 소멸하며, 쿠키가 반납돼 CH-1b도 못 타므로 접근 경로가 없다. 400 검증 실패는 `VALIDATION_ERROR` + fields[](03 규약 — 07-17 FE) |
| A-2 | POST | /api/auth/login | 🔓 | 일반 로그인. body: email, password — 게스트 승계는 guest_id 쿠키 기준(노션 07-20 개정, body guestId 폐기). **승계 동작은 가입(A-1)과 완전히 동일**(2026-07-31 개정) — 장바구니 병합 + 구간 귀속 기록 + 쿠키 반납, 재로그인에도 매번(02 D30). 공용 PC 위험은 백필 폐기와 쿠키 2시간 sliding으로 다룬다. 부수효과: 게스트 채팅 세션은 **건드리지 않는다**(CH-7이 승계 대상으로 쓴다 — 승계 안 하면 TTL 10분 소멸) |
| A-3 | POST | /api/auth/logout | 🔓(RT쿠키) | RT 삭제 + 쿠키 만료. AT가 만료돼도 로그아웃은 가능해야 하므로 RT 쿠키 기준(없어도 성공 응답) |
| A-4 | POST | /api/auth/refresh | 🔓(RT쿠키) | AT 재발급 |
| A-5 | GET | /api/auth/me | 🔑 | 내 정보(id, email, nickname, role) — FE 라우팅 가드용 |

- OAuth는 MVP 제외(2026-07-07 팀 결정). 고도화 도입 시 `GET /oauth2/authorization/{provider}` 추가.

- A-1 검증: 이메일 형식/중복(409 `MEMBER_EMAIL_DUPLICATE`), 비밀번호 규칙(8자+, 영문+숫자), gender(MALE/FEMALE), birthDate(과거 날짜), 약관 2건 미동의 400.
- A-2 실패는 계정 존재 여부 무관하게 통일 메시지(401 `AUTH_LOGIN_FAILED`) — 기능 정의 명시.
- 이메일 중복 확인은 별도 기능 없이 A-1의 409 응답으로만 처리 (2026-07-07 회의 — "기능만 일단 돌아가도록").

## 2. product / category / brand

| # | Method | 경로 | 인증 | 설명 |
|---|---|---|---|---|
| P-1 | GET | /api/categories | 🔓 | 카테고리 트리(대분류+소분류, 02 D20). 메인 해시태그는 대분류만 사용. 카테고리 아이콘은 **FE 정적 매핑**(BE 미제공 — 07-17 확정) |
| P-2 | GET | /api/products/{id} | 🔓 | 상품 상세: 대표 이미지(단일 — 02 D14), 옵션 목록, 정가/판매가, summary/attributes/description, 브랜드 요약, 평점 통계(평균·개수) — 조회 이벤트는 서버 적재 없음(FE가 E-1 `product_view`로 배치 전송, §8 — 이중 집계 방지). **HIDDEN도 404가 아니라 응답**(`purchaseState="HIDDEN"`) — 장바구니가 HIDDEN 아이템을 유지(C-1)하므로 상세 링크가 죽으면 안 됨(07-17 구현 확정). 목록(P-4/P-6/CH-5)에서는 제외 |
| P-3 | GET | /api/products/{id}/reviews | 🔓 | 후기 목록. query: page, size, sort(latest\|rating) — status=VISIBLE만. **page=0 응답에만 `distribution{5..1}`(별점 분포) 포함**(리뷰 0개면 0값 채운 객체), page≥1은 생략 — FE가 0페이지 값 재사용 (07-17 FE) |
| P-4 | GET | /api/products/popular | 🔓 | 인기 상품 N개(기본 12): 최근 7일 판매수(order_item×PAID 주문 집계, 02 §4) → 부족하면 behavior_events `product_view` 수 → 그래도 부족하면 최신순으로 채움 (비로그인 메인·신규 회원 fallback 공용). **세 단계 모두 HIDDEN과 품절(재고 0)을 제외**하므로 카드에 `purchaseState`가 없다(2026-07-28 — 상단 결정 노트. 구 이름은 `purchasable`) |
| P-5 | GET | /api/products/recommended | 🔑 | "OO님을 위한 추천". LLM 프로필 기반 — 내부적으로 FastAPI 추천 API 호출(05 문서). **타임아웃 연결 2s/응답 3s**(채팅용과 별도 — 메인 렌더 블로킹 방지), 실패·타임아웃·프로필 없음 시 P-4로 fallback. FastAPI가 상품 ID 목록을 주면 BE가 카드 조립(**P-4와 동형** — `purchaseState` 없음. CH-5도 2026-08-05부터 같은 이유로 이 필드를 빼고 `reason`만 더 있다) |
| P-6 | GET | /api/brands/{id} | 🔓 | 브랜드 소개 + 상품 목록. query: category?, sort(popular\|latest\|price_asc\|price_desc), page, size. 응답에 **`categories`(해당 브랜드 판매 중 상품의 소분류 목록)** 포함 — 브랜드홈 필터 축(02 D20)을 FE가 페이지 목록만으로는 못 만들어서(07-17 구현 확정). popular 정렬 = 표시 판매량(base_sales_count + order_item×PAID 집계 — 02 D18) |
| ~~P-7~~ | ~~GET~~ | ~~/api/products/cards?ids=1,2,3~~ | — | **폐기(2026-07-28)** — 추천 카드 하이드레이션은 CH-5(`GET /api/chat/lists/{listId}`)로 완전 대체. 상세는 상단 결정 노트 참조. |

- P-2의 평점 통계는 review 테이블 실시간 집계(파생값 저장 금지).
- 연관 추천 2종(함께 구매/대체 상품)은 상세 화면 요소지만 추천 로직이 LLM 소관이라 05 문서의 FastAPI 호출로 정의(BE는 프록시 GET `/api/products/{id}/related`).

## 3. cart

| # | Method | 경로 | 인증 | 설명 |
|---|---|---|---|---|
| C-1 | GET | /api/cart | 🔓(게스트 허용) | 내(회원/게스트) 장바구니: 아이템(상품 요약, 옵션, 수량, 현재가), 합계는 FE 계산 아님 — data에 totalOriginal/totalSale/discount 포함. 아이템에 **brandId·brandName 포함**(07-17 FE). HIDDEN·품절 아이템은 목록에 유지하되 `purchaseState`(`AVAILABLE`/`SOLD_OUT`/`HIDDEN`, 겹치면 HIDDEN 우선)로 이유를 밝히고 합계에서 제외 — 주문 시도는 O-1이 400. 아이템에 **maxQuantity**(2026-08-05 추가 — 수량 스테퍼 상한, `min(stock_quantity, 99)`를 서버가 계산. 재고만 내리면 FE가 담기 상한 99와 다시 비교해야 하는데 그 99는 서버 규칙이다. 조회 시점 스냅샷이라 C-2·C-3의 `CART_STOCK_INSUFFICIENT`는 그대로 남는다) |
| C-2 | POST | /api/cart/items | 🔓(게스트 허용) | 담기. body: productId, optionId?, quantity — 동일 상품+옵션 존재 시 수량 합산. **합산 후 수량 > 재고면 400 `CART_STOCK_INSUFFICIENT`**(아래). 담기 성공 시 행동 이벤트는 FE가 E-1 `add_to_cart`로 전송(서버 적재 없음, §8). 게스트는 guest_id 쿠키가 소유 주체(없으면 발급 — 02 D30) |
| C-3 | PATCH | /api/cart/items/{id} | 🔓(게스트 허용) | 수량 변경. body: quantity(≥1). **변경 수량 > 재고면 400 `CART_STOCK_INSUFFICIENT`**(치환값 기준) |
| C-4 | DELETE | /api/cart/items/{id} | 🔓(게스트 허용) | 삭제 (복수 삭제는 FE에서 반복 호출 — 데모 규모) |

- 옵션 있는 상품에 optionId 누락 → 400 `CART_OPTION_REQUIRED`. optionId가 해당 상품의 옵션이 아니면 400 `CART_OPTION_INVALID`(02 D26 ①). 본인(회원 또는 게스트 쿠키) 아이템 아니면 403. quantity는 1~99(합산 결과 포함 — INT 오버플로·비정상 입력 방지, I-2 동일).
- **재고 검증(2026-07-22 추가)**: C-2(합산 후 수량)·C-3(치환 수량)이 `product.stock_quantity`를 넘으면 400 `CART_STOCK_INSUFFICIENT` + `error.detail.availableStock`(남은 재고). 재고는 **상품 단위**라 옵션별 재고 없음(02 D33). 이 검증은 **UX 가드**이고 최종 방어선은 결제(O-1 PAID) 시점 조건부 차감 — 담기 이후 재고가 줄어 결제에서 다시 실패할 수 있다. 시드 기본 재고 100.
- 게스트 장바구니(02 D30): 소유 주체는 guest_id 쿠키. 가입/로그인(A-1/A-2 — guest_id 쿠키) 시 회원 장바구니로 병합(동일 상품+옵션 수량 합산·상한 99). **주문(O-1)은 로그인 필수** — 게스트가 결제 진입 시 FE가 로그인 유도.
- 부분 선택 결제는 O-1의 cartItemIds[]로 지원 — 선택 항목 합계 표시는 FE 계산, 결제 금액의 진실은 O-1 서버 재계산(원칙 유지).

## 4. order / claim

| # | Method | 경로 | 인증 | 설명 |
|---|---|---|---|---|
| O-1 | POST | /api/orders | 🔑 | 주문 생성+모의 결제 한 번에. body: **source = cartItemIds[] 또는 items[]{productId, optionId?, quantity} 중 정확히 하나** (장바구니 경유 vs 바로 구매 — 둘 다/둘 다 없음 400), addressId 또는 address 직접 입력, deliveryRequest?(02 D22), paymentMethod — 처리: PENDING 생성(아이템도 `PENDING` — 01 D9) → 스냅샷 복사 → mock 결제 판정 → PAID(아이템 `ORDERED` 전이·장바구니 경유분만 차감 — 상태 전이 기록은 order_status_logs, 01 소관) 또는 PAYMENT_FAILED. 응답: orderId, orderNo, status, **failureReason**(2026-08-05 추가 — 실패 원인 `OUT_OF_STOCK`/`MOCK_DECLINED`, 성공이면 null) |
| O-2 | POST | /api/orders/{id}/retry-payment | 🔑 | 실패 주문 재결제. body: paymentMethod — PENDING/PAYMENT_FAILED에서만. 성공 시 부수효과는 O-1의 PAID와 동일(아이템 `ORDERED` 전이·장바구니에 같은 상품+옵션 행이 남아 있으면 삭제). 응답은 O-1과 동형(**failureReason** 포함) — O-2에는 O-1의 재고 선검증이 없어 재고 부족이면 몇 번을 눌러도 같은 결과라, **이 경로의 무한 루프를 끊는 건 이 필드뿐이다**(2026-08-05) |
| O-3 | GET | /api/orders | 🔑 | 내 주문 목록: 대표 상태(01 §4 — **enum 코드 8종**, 표시 문구는 FE 매핑 — 07-17 FE), 아이템 요약. query: page, size |
| O-4 | GET | /api/orders/{id} | 🔑 | 주문 상세: 아이템별 상태, 배송지 스냅샷, 금액, 아이템별 가능 액션(canCancel/canReturn/canReview — 01 §3 매트릭스를 서버가 계산해 내려줌, 교환 제거 확정으로 canExchange 없음). 아이템에 **originalPrice(정가 스냅샷 — 할인 표시, 02 D37)** 포함 |
| O-5 | POST | /api/order-items/{id}/claims | 🔑 | 취소/반품 신청. body: type(CANCEL\|RETURN — **교환 제거 확정**), reason? — 01 매트릭스 위반 시 400 `CLAIM_NOT_ALLOWED`, 활성 클레임 존재 시 409 |
| O-6 | GET | /api/claims | 🔑 | 내 취소·반품 내역. query: page, size — 행에 **orderNo 포함**(07-17 FE) |

- **바로 구매(items[] 경로)**: 상품 상세의 "바로 구매"는 장바구니를 거치지 않는다 — FE가 `items[]`로 O-1 직접 호출(주문서 화면은 장바구니 결제와 동일, 단일 상품만 프리필). 두 경로는 라인아이템 **출처만 다르고**(cart_item 조회 vs body), 이후 스냅샷 복사·검증·결제·상태 전이는 **같은 서비스 코드로 수렴**. 스키마 무변경 — order/order_item이 스냅샷이라 cart_item에 의존하지 않는다(02 D1의 배당금).
  - 바로 구매는 **장바구니 미접촉**: 담지도, PAID 시 차감하지도 않음(차감은 cartItemIds[] 경유분 한정).
  - 게스트는 바로 구매도 **로그인 필수**(orders.member_id NOT NULL — 02 D30 일관). 게스트가 "바로 구매" 클릭 시 FE 로그인 유도.
  - **O-2 재결제는 무변경**: 실패 주문은 이미 order_item이 스냅샷돼 있어(01 D9) 출처와 무관하게 동일 재결제. 바로 구매 실패분도 그대로 재시도됨.
- O-1 검증(두 경로 공통): 대상 상품 전부 `status=ON_SALE`(HIDDEN 포함 시 400), **재고 선검증 — 상품 단위로 수량을 합산해 `stock_quantity`를 넘으면 400**(2026-08-05 추가. 같은 상품의 다른 옵션이 여러 줄일 수 있어 줄 단위로 비교하면 초과분을 놓친다. 불량 항목은 **첫 건에서 끊지 않고 전량 수집**해 `error.detail.unavailableItems[{productId, name, reason}]`로 내려준다 — `reason`은 `SOLD_OUT`\|`HIDDEN`이고 겹치면 `HIDDEN` 우선. 선검증은 UX 가드이고 최종 방어선은 여전히 결제 시점 조건부 차감이라, 경합으로 밀린 건은 200 `PAYMENT_FAILED` + `failureReason: OUT_OF_STOCK`으로 간다), optionId가 해당 상품 옵션인지(02 D26 ①, items[] 경로도 동일), 수량 아이템당 1~99, 금액은 서버가 스냅샷 가격으로 재계산(클라이언트가 보낸 금액은 신뢰하지 않음 — body에 금액 필드 자체가 없음).
- O-4가 가능 액션을 내려주는 이유: 상태 매트릭스 판단을 FE에 중복 구현하지 않기 위해(단일 진실은 서버). FE는 boolean만 보고 버튼 노출.
- 표시용 주문번호 `orderNo`는 저장하지 않고 파생: `"ORD-" + created_at(yyyyMMdd) + "-" + id` (02 D24). O-3/O-4 응답에 포함.
- O-1에서 결제까지 한 API로 묶은 이유: 모의 결제라 "생성→별도 결제 승인" 2단계로 나눌 외부 경계가 없음. 실 PG 전환 시(01 D7) 이 API를 생성/승인으로 쪼개는 게 교체 지점.

## 5. mypage (review / wishlist / recent / address / inquiry)

| # | Method | 경로 | 인증 | 설명 |
|---|---|---|---|---|
| M-1 | POST | /api/reviews | 🔑 | 후기 작성. body: orderItemId, rating(1~5), content — 자격 상태(DELIVERED/CONFIRMED — 교환 제거 확정, 01 §3) 위반 400 `REVIEW_NOT_ALLOWED`, 이미 작성한 아이템은 409 `REVIEW_ALREADY_EXISTS`(다른 *_ALREADY_*와 동일하게 CONFLICT로 분리 — Phase 4 구현 확정), 남의 아이템 404 |
| M-2 | GET | /api/reviews/me | 🔑 | 내가 쓴 후기 목록 — **MVP 제외**(FE 화면 없음, 07-17. 스펙은 유지·구현 보류) |
| M-3 | POST | /api/reviews/{id}/reports | 🔑 | 후기 신고. body: reason — 중복 신고 409 `REVIEW_REPORT_DUPLICATE`, 자기 후기 신고 400 `REVIEW_SELF_REPORT`(02 D29), 없는 후기 404 `REVIEW_NOT_FOUND` |
| M-4 | GET | /api/wishlist | 🔑 | 찜 목록 — 카드 공통 모양(P-6·M-7 동형 — P-4는 `purchaseState`가 빠져 다르다), 최근 찜 순. HIDDEN·품절도 유지(`purchaseState`로 이유 표시) — 개인 목록은 장바구니(C-1)와 동일 원칙(Phase 4 구현 확정) |
| M-5 | POST | /api/wishlist | 🔑 | 찜 추가. body: productId — 중복 409 `WISHLIST_DUPLICATE` (찜 이벤트 적재 없음 — E-1 8종에 미포함) |
| M-6 | DELETE | /api/wishlist/{productId} | 🔑 | 찜 해제 — 찜하지 않은 상품 404 `WISHLIST_NOT_FOUND` |
| M-7 | GET | /api/products/recent | 🔑 | 최근 본 상품 (behavior_events `product_view` 기반, 중복 제거 최신 20개) — 카드 공통 모양, HIDDEN 유지(M-4와 동일 원칙) |
| M-8 | GET/POST/PATCH/DELETE | /api/addresses(/{id}) | 🔑 | 배송지 CRUD. is_default 지정 시 기존 기본 해제(같은 트랜잭션). **첫 배송지는 요청값과 무관하게 기본 지정**(주소가 있으면 기본이 정확히 1개 — Phase 4 구현 확정). PATCH는 부분 수정(null 유지), is_default는 true 지정만 가능(해제는 다른 주소를 기본 지정). 삭제: 기본 배송지는 다른 배송지가 있을 때만 가능 — 등록순 가장 오래된 주소 자동 승격(같은 트랜잭션), 유일한 배송지는 삭제 불가 400 `ADDRESS_LAST_UNDELETABLE`(02 D29) |
| M-9 | GET | /api/inquiries/me | 🔑 | 내 문의 내역(읽기 전용): 제목(02 D23), 내용, 상태, 답변 |
| M-10 | PATCH | /api/members/me | 🔑 | 프로필 수정: nickname — **MVP 제외**(FE 화면 없음, 07-17. 스펙은 유지·구현 보류) |

- 문의 "접수"는 사용자 API가 없다 — 문의 챗봇(LLM)이 ⚙ internal 콜백으로만 생성(문의 단일 채널 원칙, 05 문서).
- 후기는 **등록만** — 본인 후기 수정·삭제 API 없음(02 D29, MVP 팀 결정).

## 6. chat (직결 — 상세는 05 문서. 2026-07-16 D5 직결 반영)

> **채팅 SSE는 FE↔FastAPI 직결(03 D5)** — 채팅 메시지·스트림은 Spring 엔드포인트가 아니라 FastAPI(`{LLM_SSE_URL}/chat`)로 직접 간다(05 §1-1). Spring의 역할은 **세션 + 단명 스트림 티켓(RS256) 발급**뿐.

| # | Method | 경로 | 인증 | 설명 |
|---|---|---|---|---|
| CH-1 | POST | /api/chat/sessions | 🔓(게스트 허용) | 세션 발급(Redis TTL 10분) **+ 스트림 티켓(RS256/JWKS) 동시 발급**. body: `channel`(SHOPPING\|CS). 신원 확인(회원 JWT/게스트 쿠키) 후 `sub/sub_type/scope:chat:stream` + **`sessionId`(2026-07-31 추가 — 발급 대상 세션을 서명에 포함, `threadId`는 제외)** 티켓 서명. 응답: `sessionId, ttlSeconds(600), streamTicket, ticketTtlSeconds(30~60s), llmSseUrl`. **멱등 발급**(노션 CH-1 2026-07-31 개정 · SPEC-CHAT-SESSION D5) — 같은 신원·채널의 활성 세션이 있으면 축출 없이 **그대로 반환**(owner 키 SETNX) + 세션·owner TTL sliding. 세션은 **신원+채널 단위로 공존**(SHOPPING·CS·SELLER가 서로를 밀어내지 않음)하며, "새 대화"는 FE가 `threadId`만 갱신해 이 API를 부르지 않는다. **실패: 400 `VALIDATION_ERROR`**(잘못된 값 — `channel`이 SHOPPING\|CS 밖이거나 body 파싱 불가. `SELLER`도 여기 해당, 입구는 S-4뿐) (노션 CH-1 실패 응답표 2026-07-28) |
| CH-7 | POST | /api/chat/sessions/{sessionId}/claim | 🔒 | **게스트 접속을 회원으로 승계 (신설 2026-07-31 · 이슈 #63)** — 채팅 화면에서 로그인·가입한 경우에만 FE가 호출한다(자동 아님). body 없음 — `sessionId`는 path, 신원은 AT. **소유권 근거는 쿠키가 아니라 귀속 기록**(`guest.converted_member_id`)이다 — 로그인 시 쿠키가 이미 반납되므로 "내가 방금 은퇴시킨 게스트인가"로 묻고, 그래서 sessionId만으로 남의 세션을 주울 수 없다. **순서**: I-23(AI 전이) 성공 → Redis owner 이전 → 같은 sessionId로 회원 티켓 재발급. 뒤집으면 AI 409 시 부분 성공이 고착된다. 응답은 CH-1과 동일 스키마(`sessionId`는 요청과 같은 값). 실패: 403 `SESSION_FORBIDDEN`(내 게스트가 아님) · 404 `SESSION_NOT_FOUND`(만료 → CH-1로 새 세션) · 409 `SESSION_ACTIVE`(스트리밍 중 — 끝나고 1회 재시도) · 409 `SESSION_CLAIM_CONFLICT`(이미 승계됐거나 회원이 그 채널에 이미 세션 보유 → 병합하지 않고 CH-1로) · 503 `SESSION_CLAIM_UNAVAILABLE`(AI 미응답 — Redis는 그대로 두고 재시도 가능) |
| CH-1b | POST | /api/chat/tickets | 🔓(게스트 허용) | **스트림 티켓만 재발급**(세션은 유지). body: sessionId — 매 메시지 전 또는 티켓 만료 401 시 호출. **소유권 검증**: 세션 발급 시 신원(회원 id/guest_id)과 재발급 요청 신원이 다르면 403 `SESSION_FORBIDDEN`(sessionId만 알아도 남의 세션 티켓을 못 받게 — I-20과 동일 규칙, 2026-07-17). 세션 만료·없음이면 404 `SESSION_NOT_FOUND` → FE는 CH-1로 새 세션 발급 |
| ~~CH-2~~ | ~~POST~~ | ~~/api/chat~~ | — | **폐기(직결)** — 추천 챗봇 메시지·SSE는 FE가 `POST {LLM_SSE_URL}/chat`(`Authorization: Bearer <티켓>`)로 FastAPI에 직접(05 §1-1). Spring 경유 아님. 게스트 무제한·개인화 미적용은 유지 |
| ~~CH-3~~ | ~~POST~~ | ~~/api/chat/cs~~ | — | **폐기(직결)** — CS 챗봇도 동일 직결(`channel:CS` 티켓). 비로그인은 일반 안내만(주문 질문 시 로그인 유도는 LLM 측). 단, 직결 전환 후 문의 챗봇 자체의 폐지/유지 여부는 **OPEN(LLM 확인 중)** |
| CH-5 | GET | /api/chat/lists/{listId} | 🔓(게스트 허용) | **추천 목록 조회 (확정 2026-07-18)** — FE가 SSE `products.ready{listIds[]}` 수신 후 목록마다 호출. I-21 콜백으로 저장된 목록(목록당 ≤9 · Redis TTL 10분)에 BE가 카드 완결 필드 + **추천 카드용 `reason`**(I-21 reasons echo, 없으면 null)을 부착해 반환(순서 = 콜백 저장 순서, HIDDEN·품절 드롭 — 드롭 수는 `itemsDropped`로 항상 반환, 전부 드롭돼도 404가 아니라 200 + 빈 `items`). 응답엔 `recommendationRequestId`·`listType`(항상 — FE 렌더 분기 축)·`label?`이 실리고, **BUY_ALL이면** `totalBudget?`·`sum`(드롭 후 남은 상품 재계산)·`withinBudget`(드롭·예산 미발화면 null 리터럴)이 추가된다 — PICK_ONE엔 세 키 없음(노션 CH-5). **I-21과 쌍**, P-7 대체 완료(2026-07-28 폐기). **소유자 검증**: I-21이 `sessionId`로 세션 신원을 읽어 목록에 owner를 박고, 조회 신원(회원 AT / `guest_id` 쿠키)이 다르면 403이 아니라 **404**로 존재를 은닉한다 — `listId`가 사실상 bearer 키라서(게스트 허용 공개 조회) CH-1b 소유권 규칙을 조회에도 적용. 콜백 시점에 세션이 이미 없으면 owner를 못 남기며 그 목록은 아무도 못 읽는다(fail-closed, I-21은 계약대로 200) |

- 티켓 만료(401) 시 재발급은 **CH-1b**(세션 유지) → 1회 재시도. 세션까지 만료/없음(404 `SESSION_NOT_FOUND`)이면 CH-1로 새 세션. 티켓 발급 앞단에 보조 rate limit 가능(05 §3).

## 7. seller

| # | Method | 경로 | 인증 | 설명 |
|---|---|---|---|---|
| S-1 | GET | /api/seller/summary | 🏪 | **자사 대시보드(2026-07-21 화면 확정본)** — 진입 1회 호출로 5블록: `period` / `orderStatus`(counts 6종 = order_item.status 현재 스냅샷 + activeTotal(CANCELLED·RETURNED 제외) + avgDeliveryDays(order_status_logs SHIPPING→DELIVERED 평균)) / `today`(오늘 자정~현재, `*ChangeRate`는 **어제 하루 대비**, 어제 0이면 null) + activeVisitors(behavior_events 최근 30분 distinct session_key) / `salesTrend`(to 기준 trendDays, 0 채움) / `lowStock`(ON_SALE·재고 ≤ threshold) / `products[]`(퍼널, 유지). query: from?, to?(생략 시 둘 다 오늘), lowStockThreshold?(기본 10), trendDays?(기본 7). 매출 집계 규칙은 I-6과 동일(PAID의 order_item 중 PENDING/CANCELLED/RETURNED 제외). 파라미터 오류 400 `SELLER_INVALID_PARAM` |
| S-2 | GET | /api/seller/orders | 🏪 | **자사 주문 목록 — 주문 단위(2026-07-21 [BREAKING] 아이템→주문 전환)**. 금액·건수·상태는 **자사 아이템만** 집계(타사 이름·금액 미노출). 응답: `tabCounts`(ALL/ORDERED/SHIPPING/DELIVERED/CLAIM, 전량 기준) + 행(orderId, orderNo(파생), orderedAt(created_at), recipientName(마스킹 없음), paymentMethod, myItemsAmount(CANCELLED/RETURNED 제외 합), myItemCount, representativeProduct(금액 최대 1건, product 조인), status(대표상태 파생), claimStatus). 대표상태·탭은 order_item.status 집계 SQL로 파생(orders.status엔 배송단계 없음). query: status?(ORDERED\|SHIPPING\|DELIVERED\|CLAIM), page, size(1~100), keyword?(MVP 미구현·무시). 파라미터 오류 400 `ORDER_INVALID_PARAM` |
| S-3 | GET | /api/seller/products | 🏪 | 자사 상품 목록(판매자 화면용, 2026-07-21 확정). query: status?(**displayStatus 기준** ON_SALE\|SOLD_OUT\|HIDDEN), sort(latest\|sales\|stock\|price), page, size(1~100) — **q 제거**(챗봇 I-9만 유지). 응답: `tabCounts`(ALL/ON_SALE/SOLD_OUT/HIDDEN, 전량 기준) + 행(productId, name, imageUrl, category, price, originalPrice, **stockQuantity**(02 D33), displayedSalesCount(= base_sales_count + order_item 집계), status(원본), **displayStatus**(파생, HIDDEN 우선), **createdAt**, updatedAt). SOLD_OUT은 DDL에 없어 `status='ON_SALE' AND stock=0`으로 파생. HIDDEN도 노출(본인 화면), 상세(description·attributes)는 I-11 소관. **displayStatus·tabCounts·createdAt은 S-3 전용** — I-9엔 미포함. 파라미터 오류 400 `PRODUCT_INVALID_PARAM` |
| S-4 | POST | /api/chat/seller/sessions | 🏪 | 판매자 챗봇 **세션 + SELLER 스코프 스트림 티켓 발급**(직결 — 채팅 SSE는 FE↔FastAPI). `brandId`는 JWT 검증 후 **BE가 DB에서 도출해 티켓 claim에 박음**(클라이언트/LLM 주장 무시). 실제 SSE 스트림은 05 §1-3 소관 — 주소 표기는 **OPEN**(`{AI_SERVER}/seller/chat` 별도 경로 vs 공용 `/chat`+`channel:SELLER`, LLM 확인 중). AI 분석(매출 이상/퍼널/행동/이탈)은 LLM이 internal 콜백(I-6~I-16) 사용, 상품 수정은 draft + 2왕복 confirm(HITL) — 05 §1-3. *(구 `POST /api/chat/seller` SSE 프록시는 직결로 폐기)* |
| ~~S-5~~ | ~~PATCH~~ | ~~/api/seller/products/{id}~~ | — | **폐기(2026-07-21)** — 판매자 직접 상품수정 미채택. 상품 수정은 챗봇 경로(I-11, HITL)만. 상세는 상단 결정 노트 참조. |

## 8. events (FE 행동 이벤트 배치 수집)

> 행동 이벤트는 서버 부수효과 적재가 아니라 **FE 배치 전송**으로 수집한다(2026-07-17 전환). 적재 테이블 4종(behavior_events / order_status_logs / product_change_logs / account_event_logs)은 02 문서 참조 — 이 API가 쓰는 건 behavior_events.

| # | Method | 경로 | 인증 | 설명 |
|---|---|---|---|---|
| E-1 | POST | /api/events | 🔓(인증 선택) | FE 행동 이벤트 배치 수집. body: `{"events":[{ "id":"<uuid>", "sessionKey":"...", "eventType":"...", "productId":null, "schemaVersion":2, "properties":{...}, "occurredAt":"2026-07-28T10:00:02.004Z", "recommendation":{ "recommendationRequestId":"…", "listId":"…" }? }]}` — FE가 버퍼(10건 or 5초)로 묶어 전송. **`id`는 필수**(중복 차단 키가 비면 UNIQUE가 무력화 — 02 D38), **`occurredAt`은 UTC ISO-8601(`Z`)·offset 포함 필수 형식**(타임존 없으면 400 — 정렬이 깨진다). 응답 **202 즉시, 본문 없음**(노션 기준 — 2026-07-18 Round 3). 신원은 서버 주입(member_id=JWT, guest_id=쿠키) — **익명인데 쿠키 없으면 여기서 게스트 발급**(노션 07-20 변경, 주체 없는 행 방지 — I-13 왜곡 원인 제거). **`recommendation`은 2개뿐**이며 지면·순위는 서버가 `listId`로 목록을 조회해 도출한다(FE 값 불신 — E-1 ③.5) |

- 서버 처리 4단계: ① `member_id`는 JWT에서, `guest_id`는 쿠키에서 주입(**body의 신원 주장은 무시**) ② 아래 8종 화이트리스트 외 eventType은 폐기+경고 로그 ③ `session_start`에 ipHash 주입 ④ 배치 INSERT(`created_at`=서버 수신 시각), `client_event_id`(body의 `id`) UNIQUE로 중복 차단.
- 실패 건은 세지 않는다 — 담기 실패·결제 실패 시 FE가 이벤트를 보내지 않음. `properties`에 개인정보 금지.

| eventType (8종) | 트리거 시점 |
|---|---|
| session_start | 방문 세션 시작(첫 진입) — 서버가 ipHash 주입 |
| page_view | 페이지 진입 |
| search | 검색 실행 |
| product_view | 상품 상세 진입 |
| add_to_cart | 담기 **성공 콜백** |
| checkout_start | 주문서 화면 mount(주문서 1회 = 1건) — `properties.productIds` 배열 |
| purchase_complete | 주문 완료 페이지 — `properties.orderId`·`amount` |
| login | 로그인 성공 |

## 9. admin — ⚠️ 전부 고도화 (MVP 아님)

> 2026-07-09 팀 결정: 관리자 페이지는 MVP에서 전체 제외. 클레임 완료는 자동 승인 스케줄러가 대신하고(01 D10), 문의 답변·신고 처리는 MVP 기간에 일어나지 않는다(데모에 필요한 답변 완료 건은 시드로). 아래 표는 고도화 시 구현할 명세로 유지.

| # | Method | 경로 | 인증 | 설명 |
|---|---|---|---|---|
| AD-1 | GET | /api/admin/inquiries | 🛡 | 문의 목록. query: status?, page |
| AD-2 | POST | /api/admin/inquiries/{id}/answer | 🛡 | 답변 등록 → status DONE |
| AD-3 | GET | /api/admin/reports | 🛡 | 후기 신고 목록. query: status?, page |
| AD-4 | POST | /api/admin/reports/{id}/process | 🛡 | 신고 처리. body: action(HIDE\|DELETE\|DISMISS) → review.status 변경 + report DONE |
| AD-5 | GET | /api/admin/claims | 🛡 | 클레임 목록. query: status?(기본 REQUESTED), page |
| AD-6 | POST | /api/admin/claims/{id}/approve | 🛡 | 승인 → 완료 상태 전이 (01 문서) |
| AD-7 | POST | /api/admin/claims/{id}/reject | 🛡 | 거절. body: rejectReason(필수) → 신청 전 상태 복귀 |

## 10. internal (LLM 콜백 — 노션 「API 현재」 확정 번호 체계. 스키마 상세 협의는 05 문서)

| # | Method | 경로 | 설명 |
|---|---|---|---|
| I-1 | GET | /internal/products/search | 추천 1왕복 후보 조회 — 정형조건 필터. 리랭킹 계산 입력(`price`·`rating`·`reviewCount`) 포함, 카드 표시 필드는 없음. **후보 수 상한 없음**(2026-07-27). 05 §I-1 |
| I-2 | POST | /internal/cart/items | 챗봇 장바구니 담기 |
| I-3 | GET | /internal/products/popular | 인기 상품 (무관 질문 시 카드 유지용) |
| I-4 | GET | /internal/members/{id}/orders/status | 주문 상태 요약 (문의 챗봇용) — I-19(목록)와 역할 분담 |
| I-5 | POST | /internal/inquiries | 문의 접수 |
| I-6 | GET | /internal/seller/{brandId}/sales | 매출 시계열 — granularity daily\|weekly\|monthly\|summary, 응답에 isAnomaly·deviationPct(7일 이동평균 대비 ±30%)·statusCounts(노션 확정 4키: 주문 단위 PAID/PAYMENT_FAILED + 아이템 단위 CANCELLED/RETURNED — 2026-07-18 Round 3) *(구 로컬 I-6 `…/stats` 대체)* |
| I-7 | GET | /internal/seller/{brandId}/funnel | 구매전환 퍼널 4단 — 1·2단 behavior_events, 3단 checkout_start의 `properties.productIds` 포함 여부(주문서 1회=1), 4단 order_item×product×brand(정본) *(구 로컬 I-7 상품 상세는 I-9 목록으로 흡수)* |
| I-8 | GET | /internal/account-events | 계정 이벤트 집계(**전역** — brandId 스코프 아님) — groupBy ip\|eventType\|hour, IP 마스킹, **집계 전용(raw 미반환)** |
| I-9 | GET | /internal/seller/{brandId}/products | 자사 상품 목록 — status/q/limit/offset, displayedSalesCount = base_sales_count + order_item 집계, stockQuantity 포함 (구 I-7의 소유권 403 승계) |
| I-10 | POST | /internal/seller/{brandId}/products | 상품 등록 — name·price·stockQuantity 필수, 검증 price ≤ originalPrice. **등록은 product_change_logs 미기록** |
| I-11 | PATCH | /internal/seller/{brandId}/products/{productId} | 상품 수정 통합(가격·설명·상태·재고) — 바뀐 필드마다 product_change_logs 기록(동일값 미기록), 응답에 changes[]. HITL confirm 후 실행(05 §1-3) — 상품 수정의 유일 경로(구 S-5 직접수정 폐기 2026-07-21) |
| I-12 | DELETE | /internal/seller/{brandId}/products/{productId} | soft delete(status=HIDDEN) — **HITL 승인 후에만**(05 §1-3), STATUS 변경 로그 기록 |
| I-13 | GET | /internal/seller/{brandId}/events | 행동 이벤트 조회/집계 — 노션 명세 확정(2026-07-18)·구현 완료: groupBy=product\|eventType\|date, counts camelCase, uniqueVisitors·viewToCartRate. **purchaseComplete는 order 기준(2026-07-31 개정, 이슈 #62)**: purchase_complete는 주문당 1회 발사라 `product_id`가 비어 이벤트로는 상품 귀속이 불가능하다 — 기간 내 PAID 주문의 `order_item × product × brand` **주문 건수**(`COUNT(DISTINCT order_id)`, `paid_at` 기준)로 세며 I-7 4단과 같은 정본이다. 수량 지표가 아니다(수량은 S-1 `salesCount`). 구 "이벤트 기준" 규정은 폐기 — 상품 조인 스코프에서 NULL 행이 탈락해 **상시 0**이었다. **uniqueVisitors 귀속(2026-07-31)**: 승계된 게스트 구간은 귀속 계정으로 접어 센다 — 게스트 신원이 2시간마다 회전하므로 접지 않으면 "로그아웃 상태로 보다가 로그인해 산 한 사람"이 둘로 세어진다. 끝내 로그인하지 않은 게스트는 구간마다 별개로 남는다(신원을 밝히지 않은 방문이라 합칠 근거가 없다 — GUEST-LIFECYCLE 열린 질문 1) |
| I-14 | GET | /internal/seller/{brandId}/order-events | 주문 상태 전이 로그 조회 — toStatus 복수/actorType/stats/groupBy=memberId(어뷰징 탐지), 상태 어휘는 우리 상태명 기준 |
| I-15 | GET | /internal/seller/{brandId}/product-changes | 상품 변경 이력 — changeType/productId/기간 필터, 품절 신호 = STOCK 변경의 newValue "0" |
| I-16 | GET | /internal/seller/{brandId}/churn | 이탈 코호트 — inactiveDays(기본 30), preChurnSignals, 마지막 로그인 = account_event_logs.LOGIN_SUCCESS. **귀속 조인(2026-07-31)**: 코호트·lastActivity·sessions30d·preChurnEvent·가격인상노출 5개 집계가 `COALESCE(be.member_id, guest.converted_member_id)` 기준이다 — 백필 폐기로 로그인 전 탐색은 `guest.converted_member_id`로만 회원과 이어지며, 접지 않으면 로그아웃 상태로 계속 둘러본 회원이 무활동으로 잡혀 이탈로 오판된다(GUEST-LIFECYCLE) |
| I-17 | GET | /internal/products/changes | 상품 정보 배치 pull(AI 벡터DB 동기화) — since 커서(Base64URL of `(updatedAt,id)`)+limit(기본 500), items[].status `ON_SALE\|HIDDEN`, ON_SALE은 name·category·brand·price·rating·reviewCount·attributes 동반(평점·리뷰수 조회 집계), 초기 전체 구축 since="0". 잘못된 커서 400 `INVALID_CURSOR`. 상세 05 §I-17 |
| I-18 | GET | /internal/cart | 챗봇 장바구니 조회 — userId/guestId 메아리(게스트 허용), 응답 item에 productName·optionName 필수, 빈 장바구니도 200. C-1 superset이라 **maxQuantity**도 동반(2026-08-05 — "최대 3개까지 담을 수 있어요" 안내용) |
| I-19 | GET | /internal/members/{id}/orders | 구매 이력 목록(CS 챗봇 "내 주문 어때?") — status 단일 필터(어휘: ORDERED\|SHIPPING\|DELIVERED\|CONFIRMED\|CANCELLED\|RETURNED), 응답 camelCase·숫자 id, 아이템에 `categoryName`(소분류명 — 노션 I-19), shippingFee 항상 0(배송비 없음 확정). I-4(요약)와 역할 분담 |
| I-20 | POST | {LLM_BASE_URL}/events/session-end | **방향 예외: Spring→FastAPI(아웃바운드)** **프로필 갱신 신호** — 승격 트리거 셋(로그아웃·새 대화·유휴) 중 Spring이 감지 가능한 **로그아웃만** HTTP로 넘긴다(회원만, 노션 I-20 정본 2026-07-31). 새 대화·유휴는 FastAPI 자체 감지(HTTP 없음). Spring이 싣는 reason은 `logout` 하나, **멱등**(응답 202 {status: accepted\|duplicate}). sessionId는 **UUID 그대로 수신(2026-07-17 LLM 합의 — 구 S- 형식 폐기)**. 상세는 05 §2-1(아웃바운드 관례) |
| I-21 | POST | /internal/recommendations | **추천 목록 콜백 (확정 2026-07-18 · 2026-07-28~30 다중 목록 개정)** — FastAPI가 리랭킹 확정 목록을 저장: `{sessionId(UUID), recommendationRequestId, listType(PICK_ONE\|BUY_ALL), totalBudget?, lists[{listId(FastAPI 생성 — 영숫자·`-`·`_` ≤64, ≥128bit 무작위), label?, productIds[](**≤9, 목록당**, 순서 유지), reasons[{productId, reason}]?}](**≤10**)}` → **Redis(TTL 10분, CH-5 조회 전용) + DB(영구, 정본)** 양쪽 저장 + `recommendation_generated` 적재(목록당 1행, 재전송분 제외). reasons는 **추천 카드용 이유**(SSE의 채팅용 이유와 이원화) — CH-5 카드에 echo. **products.ready 발행 전 호출, 콜백 실패 시 products.ready 발행 금지**(05 §1-2-1). 세션 만료 시 익명 저장(CH-5 조회 불가). CH-5와 쌍 |
| I-24 | DELETE | /internal/cart/items/{cartItemId} | 챗봇 장바구니 삭제 (신설 2026-08-05 · AI 이슈 #285) — userId/guestId 메아리 XOR(위반 400 `CART_QUERY_INVALID`), 게스트 허용. C-4와 같은 CartService라 **소유자 불일치 403 `AUTH_FORBIDDEN`·미존재 404 `CART_ITEM_NOT_FOUND`(비멱등)**를 그대로 상속하고, 성공은 `data: null`(cartItemId 미탑재). 재고·상품 상태를 보지 않아 HIDDEN·품절도 삭제된다. **복수 삭제는 항목별 반복 호출**(C-4와 같이 bulk 없음) |
| I-25 | PATCH | /internal/cart/items/{cartItemId} | 챗봇 장바구니 수량 변경 (신설 2026-08-05 · AI 이슈 #285) — body `{quantity}`(1~99, C-3과 동일 DTO), 신원은 query XOR. **합산이 아니라 치환**이라 보낸 값 자체를 재고와 비교(초과 시 400 `CART_STOCK_INSUFFICIENT` + `detail.availableStock`) — "하나 더"는 합산이라 I-2 재호출이 맞다. 응답 `{cartItemId, quantity}` |
| I-26 | POST | /internal/wishlist | 챗봇 찜 추가 (신설 2026-08-05 · AI 이슈 #285) — body `{userId, productId}`, **회원 전용**(게스트 찜은 없다 — M-4). M-5와 같은 WishlistService라 중복 409 `WISHLIST_DUPLICATE`(멱등 200 아님)·경합 409 `RESOURCE_CONFLICT`·미존재 404 `PRODUCT_NOT_FOUND`를 상속하고, 성공은 `{productId}`(별도 wishlistId 없음). **HIDDEN·품절도 찜 가능** — 재입고를 기다리는 정상 패턴이라 담기(I-2)의 재고 검증과 다르다 |
| I-27 | DELETE | /internal/wishlist/{productId} | 챗봇 찜 해제 (신설 2026-08-05 · AI 이슈 #285) — 키는 `wishlistId`가 아니라 `productId`, 신원은 query `userId`(누락 400 `WISHLIST_QUERY_INVALID`). M-6과 같이 **상품 존재를 보지 않고 찜 행만 찾으므로** 없는 상품과 안 찜한 상품이 모두 404 `WISHLIST_NOT_FOUND`(비멱등) — 추가(I-26)와 비대칭이다. 성공은 `data: null` |
| I-28 | GET | /internal/wishlist | 챗봇 찜 목록 (신설 2026-08-05 · AI 이슈 #285) — 신원은 query `userId`, I-27 지칭 해소용. 응답은 M-4와 같은 카드 목록(`data.items`) 전체 스키마 재사용(AI가 쓰는 건 `productId`·`name`과 판매 가능 여부뿐이지만 별도 DTO보다 구현 비용이 낮다 — 카드 필드는 M-4를 따라 움직인다). 찜이 없어도 200 + 빈 배열, 찜한 뒤 HIDDEN·품절이 돼도 목록에 남고 `purchasable`로 이유를 표시한다. 페이징 없이 MVP 전량 반환(M-4·I-18 전례) |

- 번호 체계는 노션 「API 현재」 DB 기준(2026-07-17)으로 확정 — 구 로컬 I-6(`…/stats`)·I-7(판매자 상품 상세)은 각각 I-6(sales)·I-9(목록)로 대체/흡수.
- I-20만 호출 방향이 반대(Spring→FastAPI) — 스키마·표기는 05의 아웃바운드 관례(05 §2-1)를 따른다.

## 11. 공통 에러 코드 (초기 세트)

> 2026-07-28 전수 재검토 — 노션 📡 API 명세서의 현행 57개 API에 실패 응답을 전부 기재하면서 이 목록을 `ErrorCode` enum과 대조했다.
> **422를 쓰는 건 I-10/I-11 3종뿐**(`MISSING_FIELD`·`INVALID_PRICE`·`INVALID_STOCK`) — "되물어서 채우면 되는 것"과 "호출이 잘못된 것"을 LLM이 구분하게 하려는 의도다. 나머지 입력 오류는 전부 400이다.
> **`/internal/**`에는 403이 없다** — 서비스 토큰 필터 하나로만 지키므로 통과 아니면 401이며, 남의 리소스 접근은 404로 은닉한다(구 노션 표의 `403 AUTH_FORBIDDEN`·`403 FORBIDDEN` 행은 실재하지 않아 삭제).
>
> **2026-07-31 전수 스윕(노션 60개 API) — 실패 응답 표기 규약 확정.** FE가 표 하나만 보고 분기할 수 있게, 각 API 페이지의 실패 응답 표는 다음을 지킨다:
> 1. **code 칸에 설명을 쓰지 않는다** — `(검증 공통)`·`(아이템 없음)` 같은 플레이스홀더 금지. 실제 `ErrorCode` 값을 적는다(C-3 FE 문의로 확정).
> 2. **공통 코드도 표에 행으로 넣는다** — 다른 문서를 찾아보게 만들지 않는다. 인증 방식별로: 회원 전용 = `401 AUTH_REQUIRED` + `401 AUTH_TOKEN_EXPIRED` + `403 AUTH_FORBIDDEN`, 게스트 허용 = `401 AUTH_TOKEN_EXPIRED`만(만료 토큰을 실었을 때 인증 필터가 permitAll보다 먼저 거부), `/internal/**` = `401 INTERNAL_TOKEN_INVALID`. **모든 API에 `500 INTERNAL_ERROR`.**
> 3. **발생하지 않는 코드는 `—`로 명시한다** — "안 적힌 건가 안 나는 건가"를 없앤다.
> 4. **행은 HTTP 상태 오름차순** — `400 → 401 → 403 → 404 → 409 → 422 → 500 → 503`.
>
> 스윕 제외: LLM 팀 소유(CH-2·CH-3·S-4·I-20·I-22·I-23 — 실패 코드가 AI 구현 소관) · 폐기(S-5·CH-4) · 보류(M-2·M-10) · admin(AD-1~7).
> ⚠️ **CH-3의 `CHAT_SESSION_EXPIRED`는 실재하지 않는 코드**다(enum 미정의, CH-2에서 폐기 확정 — "sessionId에는 만료 의미가 없고 인증 실패는 401로 통일"). CH-3 자체의 존폐가 §12 OPEN이라 페이지는 손대지 않았다.

`VALIDATION_ERROR`(400 — `error.fields[{field,message}]` 동반, 03 규약. 단 `fields`는 필드 단위 검증(@Valid/파라미터)에서만 붙고, body 파싱 실패(enum 불일치·JSON 문법)나 도메인 거절(CH-1 `channel:SELLER` 등)은 `code`+`message`만 — FE는 `fields` 부재를 전제할 것) `AUTH_REQUIRED`(401 — 토큰 없음, 로그인 유도) `AUTH_LOGIN_FAILED` `AUTH_TOKEN_EXPIRED`(401 — 만료, refresh 후 1회 재시도. AUTH_REQUIRED와 분리 — 07-17 FE) `AUTH_FORBIDDEN` `MEMBER_EMAIL_DUPLICATE` `MEMBER_NOT_FOUND`(404 — I-4·I-19의 미존재 회원, O-1 주문 생성 시 회원 조회 실패. 2026-07-31 목록 누락 보완) `PRODUCT_NOT_FOUND` `BRAND_NOT_FOUND`(404 — Phase 2 추가) `CART_OPTION_REQUIRED` `CART_OPTION_INVALID` `CART_STOCK_INSUFFICIENT`(400 — 담기/수량변경 시 재고 초과, `error.detail.availableStock` 동반, 2026-07-22 추가) `CART_ITEM_NOT_FOUND`(404 — Phase 3 추가) `ORDER_NOT_FOUND` `ORDER_ITEM_NOT_FOUND` `ADDRESS_NOT_FOUND`(각 404 — Phase 3 추가) `ORDER_PRODUCT_UNAVAILABLE`(400 — 대상 상품 미판매/HIDDEN **또는 재고 부족**, Phase 3 추가. 2026-08-05 의미 확장 — 구 규정은 "재고 0은 여기 해당하지 않고 200 `PAYMENT_FAILED`"였으나 그 응답이 카드 거절과 구분되지 않아 사용자가 재결제 무한 루프에 갇혔다. `error.detail.unavailableItems[{productId, name, reason}]` 동반) `ORDER_INVALID_TRANSITION` `CLAIM_NOT_ALLOWED` `CLAIM_ALREADY_REQUESTED` `REVIEW_NOT_ALLOWED` `REVIEW_ALREADY_EXISTS`(409) `REVIEW_SELF_REPORT` `REVIEW_NOT_FOUND`(404 — Phase 4 추가) `REVIEW_REPORT_DUPLICATE`(409 — Phase 4 추가) `WISHLIST_DUPLICATE`(409 — Phase 4 추가) `WISHLIST_NOT_FOUND`(404 — Phase 4 추가) `ADDRESS_LAST_UNDELETABLE` `SESSION_NOT_FOUND` `SESSION_FORBIDDEN` `SESSION_ACTIVE`(409 — CH-7 승계 시 AI가 "진행 중인 스트림 있음"으로 거부, 스트리밍 종료 후 1회 재시도 권장. #63) `SESSION_CLAIM_CONFLICT`(409 — CH-7 승계 불가: AI의 claim 이력·terminal·owner 불일치, **또는 Spring 선검사에서 그 채널에 회원 세션이 이미 있음**. 되돌릴 수 없어 FE는 CH-1로 새 세션) `SESSION_CLAIM_UNAVAILABLE`(**503** — CH-7에서 AI 미응답·503. 재시도 가능하며 Redis owner는 옮기지 않는다(부분 성공 금지). 2026-07-31 노션 I-20/CH-7 표의 `503 INTERNAL_ERROR` 오기를 이 코드로 정정 — `INTERNAL_ERROR`는 500이라 503과 함께 쓸 수 없다) `INTERNAL_TOKEN_INVALID`(401 — Phase 5) `INVALID_CURSOR`(400 — I-17 상품 동기화 커서 해석 불가/변조, AI는 since="0" 폴백, 2026-07-23 추가) `NOT_IMPLEMENTED`(501 — OPEN 스텁, Phase 5 추가) `SELLER_BRAND_NOT_FOUND`(404 — SELLER 계정에 연결된 brand.seller_id 없음, Phase 6 추가) `PRODUCT_CATEGORY_INVALID`(400 — I-10 등록 카테고리가 없거나 대분류 D26②, Phase 6 추가) `SELLER_INVALID_PARAM`(400 — S-1 from>to·날짜 형식·lowStockThreshold/trendDays 범위 밖, 2026-07-21 추가) `PRODUCT_INVALID_PARAM`(400 — S-3 status/sort enum·page/size 범위 밖, 2026-07-21 추가) `ORDER_INVALID_PARAM`(400 — S-2·I-19 status enum·page/size 범위 밖) `CART_QUERY_INVALID`(400 — I-18·I-24·I-25 userId/guestId XOR 위반. 2026-08-05 장바구니 변경 2종으로 확대) `WISHLIST_QUERY_INVALID`(400 — I-27·I-28 query `userId` 누락. 찜은 회원 전용이라 XOR이 아니라 단일 필수이며, I-18의 자원별 query code 전례를 따른다. body 신원(I-26)은 I-2와 같이 `VALIDATION_ERROR`+`fields`로 걸러 코드를 나눈다. 2026-08-05 추가) `INVALID_PERIOD`(400 — 분석 API(I-6~I-8·I-13~I-16)의 from/to **누락·형식 오류·역전을 하나로 통일**. 이 세 가지가 응답상 구분되지 않는 게 의도된 계약) `INVALID_GROUP_BY`(400 — I-8·I-13·I-14의 groupBy/eventType enum 밖) `MISSING_FIELD`(**422** — I-10 필수값 누락, 에이전트가 사용자에게 되물을 것) `INVALID_PRICE`(**422** — price > original_price 교차 검증 D28) `INVALID_STOCK`(**422** — stock_quantity 음수) `ALREADY_HIDDEN`(409 — I-12 이미 HIDDEN, 멱등 200 아님) `RESOURCE_NOT_FOUND`(404 — 미존재 경로/공통, Phase 0) `RESOURCE_CONFLICT`(409 — DB 제약(UNIQUE 등) 위반 공통 매핑, 2026-07-18 추가. check-then-act가 경합에 진 순간(가입 이메일·찜·리뷰 신고 등)을 `DataIntegrityViolationException` → 500이 아니라 409로 내보내기 위한 폴백. 도메인 전용 409 코드(`WISHLIST_DUPLICATE` 등)가 우선하고, 그 앞단 검증을 빠져나간 실제 경합만 이 코드로 떨어진다) `RATE_LIMITED`(**429** — A-1·A-2 시도 제한. `Retry-After` 헤더 동반, 2026-08-04 추가. IP·계정 두 축을 함께 세며 어느 쪽이든 초과 시 거절. **계정 잠금이 아니라 일시 차단**이라 창이 지나면 자동 해제된다 — FE는 "잠시 후 다시 시도해주세요"로 안내하고 `Retry-After`만큼 재시도를 막으면 된다) `INTERNAL_ERROR`(500 — 공통, Phase 0) — 구현 중 추가 시 이 목록에 반영. **에러 부가 데이터**: `CART_OPTION_REQUIRED`는 `error.detail.options[{optionId, name, extraPrice}]` 동반(03 D2, Phase 5 추가). **날짜 규약**: 모든 날짜·시각 필드는 ISO 8601 + 타임존 오프셋(`2026-07-10T14:23:00+09:00` — 03 규약, 07-17 FE).

## 12. 미결(OPEN) — 구현 전 확정 필요

- [x] ~~채팅 SSE 방식~~ — **FE↔FastAPI 직결 + RS256/JWKS 단명 티켓 확정(2026-07-16, 03 D5)**. CH-1이 세션+티켓 발급, CH-2/CH-3/구 S-4 SSE 프록시 폐기. 추천 카드는 P-7로 FE가 하이드레이션 *(2026-07-17: I-21 콜백 + CH-5 목록 조회로 대체 예정 — 아래 OPEN)*
- [ ] P-5 개인화 추천의 응답 형태 — **상품 ID 목록 + BE 카드 조립(P-4·CH-5 카드와 동형)으로 제안 확정 방향**(05 §4), FastAPI 응답 스키마만 LLM 팀 확정 대기
- [x] ~~상품 상세 "바로 구매" 지원 여부~~ — **있음 확인(2026-07-10)**. O-1 body에 items[] 경로 추가로 반영(§4), 스키마 변경 없음
- [x] ~~최근 본 상품 "개별 삭제(X)" 여부~~ — 기능 없음 확인(2026-07-10, 02 D29 종결)
- [x] ~~CH-5/I-21 추천 목록(콜백+조회) 스키마~~ — **확정(2026-07-18 LLM 합의)**: listId는 FastAPI 생성 문자열, reason 이원화(SSE=채팅용 / 콜백 reasons=카드용 → CH-5 echo), TTL 10분. P-7은 FE 전환 완료로 폐지(2026-07-28 — 상단 결정 노트). **2026-07-28~30 개정**: 다중 `lists[]`·`listType`(PICK_ONE\|BUY_ALL)·목록당 9개·목록 10개 상한, DB 영구 보존 추가 — **I-21 구현 완료**(Redis+DB, 02 D38 테이블). FastAPI 전환 전까지 구 평평 구조도 함께 수용(과도기). **CH-5 확장 구현 완료(2026-07-30)** — 응답에 `recommendationRequestId`·`listType`(항상)·`label?`·(BUY_ALL이면) `totalBudget`·`sum`·`withinBudget` 추가. **`recommendation_generated` 서버 적재도 완료(2026-07-30, 02 D39)**. **E-1 확장도 완료(2026-07-30)** — FE 추천 4종 수용 · `occurredAt` 저장 + 이상치 보정(`_timeShifted`) · 추천 귀속 서버 도출(③.5) · `_incomplete` · `schemaVersion`을 `properties`에. `occurred_at`·`client_event_id` **NOT NULL 전환도 완료**(2026-07-30, 02 D40 — 기존 행은 `scripts/migrate-2026-07-31-...`이 백필). 추천 축(I-21·CH-5·E-1) 구현 **완결**
- [ ] CH-3(CS 챗봇) 직결 전환 후 폐지/유지 — **OPEN(LLM 확인 중)**
- [x] ~~I-13(행동 이벤트 조회/집계) 본문 명세~~ — **노션 재작성 확인·구현 완료(2026-07-18)**
- [x] ~~I-17(벡터DB 동기화 배치 pull) 커서 방식·리뷰 포함 여부~~ — **확정(2026-07-23)**: 커서=Base64URL `(updatedAt,id)` keyset, status `ON_SALE|HIDDEN`, ON_SALE에 `rating`·`reviewCount`(조회 집계)·`attributes`(JSON object) 포함
- [x] ~~I-20 sessionId 형식~~ — **UUID로 합의 완료(2026-07-17)**
