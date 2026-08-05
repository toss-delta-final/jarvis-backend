# 상품 삭제 상태 분리 (`ProductStatus.DELETED`) — design

- 날짜: 2026-08-05
- 브랜치: `feat/product-deleted-status`
- 배경: 상세 이미지 설계 중 발견한 별건(2026-08-05 상세 이미지 스펙 §9). `product.status`가
  `ON_SALE`/`HIDDEN` 2종뿐이라 **"판매자가 숨긴 것"과 "판매자가 삭제한 것"이 같은 값으로 뭉개진다.**

## 1. 문제

`HIDDEN`에 도달하는 경로가 둘인데 서로 구분되지 않는다.

- **I-11** 상품 수정(상태 변경) → `HIDDEN` — "숨김/판매정지"
- **I-12** 삭제 → `HIDDEN` — "삭제"

결과로 두 가지 증상이 있다.

**① 삭제한 상품이 판매자 목록에 남는다.** S-3는 `HIDDEN`을 본인 화면에 노출하므로(04 §7
*"HIDDEN도 노출(본인 화면)"*), 판매자가 삭제한 상품이 숨긴 상품과 나란히 HIDDEN 탭에 보인다.

**② 숨긴 상품은 삭제할 수 없다.** [`SellerProductService.softDelete`](../../../src/main/java/com/jarvis/seller/SellerProductService.java)가
이미 `HIDDEN`이면 409 `ALREADY_HIDDEN`을 던진다. "숨겼다가 나중에 지운다"는 평범한 흐름이 막혀 있다.

빠진 축은 **가시성**이다.

| 상태 | 소비자 | 판매자 |
|---|---|---|
| `ON_SALE` | 보임 | 보임 |
| `HIDDEN` (숨김·판매정지) | 안 보임 | **보임** |
| `DELETED` (삭제됨) | 안 보임 | **안 보임** |

세 번째 줄을 표현할 값이 없다. → `ProductStatus`에 **`DELETED`를 추가**한다.

**별도 컬럼(`deleted_at`)은 채택하지 않는다.** 검토했으나 그 형태의 이점은 "삭제 후 이전 상태로 복구"인데
**이 시스템에 삭제 복구 기능이 없고 계획도 없다**(YAGNI). 판매자의 삭제는 "상품이 처한 상태"이지
별개의 축이 아니므로 기존 `status`와 같은 축에 놓는다.

## 2. 상태는 3층 구조이며, 새 값은 대부분 자동으로 흡수된다

| 층 | 값 | 파생 규칙 |
|---|---|---|
| DB 원본 `product.status` | `ON_SALE` / `HIDDEN` (+ **`DELETED`**) | — |
| 소비자 파생 [`PurchaseState`](../../../src/main/java/com/jarvis/product/PurchaseState.java) | `AVAILABLE` / `SOLD_OUT` / `HIDDEN` | `!= ON_SALE` → `HIDDEN`, 아니면 재고로 갈림 |
| 판매자 파생 [`SellerDisplayStatus`](../../../src/main/java/com/jarvis/seller/SellerDisplayStatus.java) | `ON_SALE` / `SOLD_OUT` / `HIDDEN` | `== HIDDEN` → `HIDDEN`, 아니면 재고로 갈림 |

**소비자 쪽 흡수는 옳다 — 손대지 않는다.** 숨김이든 삭제든 소비자에겐 똑같이 "판매자가 내림"이고
살 수 없다. `PurchaseState`는 이미 `!= ON_SALE` 조건이라 `DELETED`를 `HIDDEN`으로 흡수한다.

**판매자 쪽 흡수는 위험하다 — 고친다.** `SellerDisplayStatus.of`는 `== HIDDEN` 조건이라 `DELETED`가
분기를 통과해 **재고가 있으면 `ON_SALE`로 잘못 분류된다.** 조건을 `!= ON_SALE`로 바꿔
`PurchaseState`와 같은 모양으로 맞춘다(§3-②). 목록에서 삭제분을 아예 제외하므로 실제로 이 경로에
`DELETED`가 도달하지는 않지만, **안전망을 남긴다.**

## 3. 변경 지점

### ① `ProductStatus`에 `DELETED` 추가 — **DDL 변경 없음**

[`schema.sql`](../../backend/schema.sql)의 `product.status`가 `VARCHAR(20)`이라(ENUM 아님)
**마이그레이션 SQL이 필요 없다.** 주석만 갱신한다.

### ② `SellerDisplayStatus.of` — 조건을 `!= ON_SALE`로

```java
if (status != ProductStatus.ON_SALE) {   // was: status == ProductStatus.HIDDEN
    return HIDDEN;
}
```
재고가 남은 삭제 상품이 `ON_SALE`로 새는 것을 막는다.

### ③ S-3 판매자 목록에서 삭제분 제외 — **공용 메서드는 건드리지 않는다**

[`SellerProductService:81`](../../../src/main/java/com/jarvis/seller/SellerProductService.java)이
`findAllByBrandId(brandId)`로 브랜드 전 상품을 조건 없이 로드하므로 삭제분이 목록과 `tabCounts`에
그대로 들어간다. **그러나 이 메서드를 고치면 안 된다** — 호출부 4곳 중 3곳이 과거 데이터 집계다.

| 호출부 | 용도 | 삭제분 |
|---|---|---|
| `SellerProductService:81` | S-3 상품 목록 | **제외** |
| `SellerSalesService:161` | S-1 대시보드 판매 지표 | **포함** — 지난 매출이 사라지면 안 됨 |
| `SellerAnalyticsService:339` | 이벤트 통계 상품명 매핑 | **포함** — 주문·이벤트에 남아 있음 |
| `SellerAnalyticsService:455` | 퍼널 대상 상품 집합 | **포함** — 같은 이유 |

→ S-3 전용 조회(`findAllByBrandIdAndStatusNot(brandId, DELETED)`)를 추가하고 S-3만 그것을 쓴다.

`tabCounts`는 **3탭(`ALL`/`ON_SALE`/`SOLD_OUT`/`HIDDEN`)을 그대로 유지**한다 — 삭제분은 목록 자체에서
빠지므로 "삭제됨" 탭을 만들지 않는다(판매자에게 보이지 않아야 한다는 것이 이 작업의 목적). `ALL`은
삭제분이 빠진 전량이 된다.

### ④ I-9 챗봇 상품 목록에서 삭제분 제외

[`ProductRepository:204`](../../../src/main/java/com/jarvis/product/ProductRepository.java)의
`(:status is null or p.status = :status)`는 status 미지정 시 전부 반환한다 → `and p.status <> DELETED` 추가.
`status=DELETED`를 명시로 넘기면 빈 결과가 된다(별도 400 없음 — 노출하지 않는 것이 목적).

### ⑤ I-12 삭제 — `DELETED`로 전이

```java
if (product.getStatus() == ProductStatus.DELETED) {
    throw new BusinessException(ErrorCode.ALREADY_DELETED);   // 409
}
// HIDDEN → DELETED 는 정상 전이
```
`STATUS` 변경 로그는 기존과 동일하게 남긴다(`old=ON_SALE|HIDDEN`, `new=DELETED`).

**계약 변경**: 409 어휘가 `ALREADY_HIDDEN` → `ALREADY_DELETED`. 기존 코드는 더 이상 I-12에서 쓰이지 않는다.

### ⑥ I-11 수정 — 삭제된 상품 차단

삭제된 상품은 판매자에게 보이지 않으므로 챗봇이 되살리거나 수정할 수 있으면 안 된다.

- `product.getStatus() == DELETED` 이면 409 `PRODUCT_DELETED`
- `request.status() == DELETED` 도 거부 — **삭제는 I-12 전용 경로**이며 수정으로 도달할 수 없다

404가 아니라 409인 이유: I-11은 HITL 경로라 에이전트가 판매자에게 사유를 설명한다. 404면
"없는 상품"이라 말하게 되는데 상품은 존재하고 주문 내역에도 남아 있으므로 사실과 다르다.

### ⑦ I-10 등록 — `status=DELETED` 거부

[`SellerProductService.create`](../../../src/main/java/com/jarvis/seller/SellerProductService.java)가
`request.status()`를 그대로 받는다. 삭제 상태로 상품을 만드는 것은 의미가 없으므로 거부한다.

### ⑧ 손대지 않는 것

**소비자 조회 전체.** [`ProductRepository`](../../../src/main/java/com/jarvis/product/ProductRepository.java)의
인기·목록·검색·브랜드·추천 쿼리가 예외 없이 `status = 'ON_SALE'` **화이트리스트**라 `DELETED`가
자동으로 제외된다. 블랙리스트(`!= HIDDEN`)였다면 전부 뚫렸을 것이다.

**I-17 AI 상품 동기화.** [`ProductService:186`](../../../src/main/java/com/jarvis/product/ProductService.java)이
`!= ON_SALE` 조건이라 `DELETED`가 자동으로 최소 필드 항목(`Item.hidden`)으로 실려 나가고, AI는 이미
이를 "인덱스에서 제거"로 처리한다. **원하는 동작이 그대로 나오므로 변경 없다.**

`Item.hidden`이 `status:"HIDDEN"`을 하드코딩하므로 삭제분도 AI에겐 `HIDDEN`으로 보인다. **그대로 둔다** —
AI가 두 값에 다르게 반응할 이유가 없고(하는 일이 "제거" 하나뿐), 값을 늘리면 LLM팀이 새 어휘를
처리해야 하는데 얻는 것이 없다. 05 계약 무변경. 나중에 필요하면 `Item.deleted()` 추가로 끝난다.

## 4. 주문 내역 — 링크는 FE가 끈다

주문 내역·장바구니에서 **상품은 그대로 보이되 상세로 이동만 막는다.**

order_item이 `product_name`·`option_name`·`price`·`original_price`·`quantity`를 **주문 시점 스냅샷으로
보유**하므로, 상품이 숨겨지든 삭제되든 주문 내역 행은 그대로 렌더된다. 이미지는
[`OrderService.imageUrls`](../../../src/main/java/com/jarvis/order/OrderService.java)가 product에서
가져오지만 soft delete라 행이 살아 있다. **즉 "보이는 것"은 이미 보장돼 있고, 바뀌는 것은 상품명을
링크로 감싸느냐뿐이다.**

→ 주문 내역 응답에 `purchaseState`를 추가해 FE가 판단하게 한다. `imageUrls()`가 이미 `Product`
엔티티를 조회하므로 **추가 쿼리가 없다.**

**P-2는 계속 정상 응답한다(404로 바꾸지 않는다).**
① 링크를 끊어도 URL 직접 접근은 남는데, 그때 404보다 "판매 중지된 상품입니다" 화면이 낫다
② 상세페이지에 직접 오는 사람은 대개 자기 주문 내역에서 오므로 상품명·이미지를 보여주는 편이 친절하다
③ `DELETED`에 404를 주는 것은 사실과 다르다 — 상품은 존재하고 주문 내역에도 있다.

UX 원칙: **막는 것은 표시와 함께**한다. 아무 안내 없이 클릭이 안 되는 것과 클릭 후 에러를 보는 것
둘 다 나쁘고, "판매 종료" 표시 + 비활성이 옳다. `purchaseState`가 그 표시의 어휘를 그대로 제공한다
(`SOLD_OUT`은 기다리면 되고 `HIDDEN`은 다른 걸 찾아야 한다 — `PurchaseState` 도입 근거와 동일).

## 5. 영향 범위

| 구분 | 대상 |
|---|---|
| **ERD (불변 기준)** | `schema.sql` — `product.status` 주석만(DDL 변경 없음, `VARCHAR(20)`) |
| **노션 (불변 기준)** | I-12(409 어휘), I-11(삭제 상품 차단), S-3(삭제분 제외), 기능 정의 상태값 |
| 내부 문서 | 02(상태값·결정 기록), 04 §7·§10(S-3·I-9·I-10·I-11·I-12) |
| 코드 | `ProductStatus`, `SellerDisplayStatus`, `SellerProductService`(list·create·update·softDelete), `ProductRepository`(S-3 전용 조회 + I-9 조건), `ErrorCode`(`ALREADY_DELETED`·`PRODUCT_DELETED`), `OrderDetailResponse`·`OrderListResponse`·`OrderService` |
| 테스트 | HIDDEN→DELETED 전이, 재삭제 409, 삭제 상품 수정 409, S-3 제외와 `tabCounts`, I-9 제외, 대시보드·통계는 **포함** 유지, 소비자 조회 제외 |

**jarvis-frontend** — 주문 내역 행에 `purchaseState` 표시·링크 비활성 처리. 판매자 화면은 삭제분이
서버에서 빠지므로 변경 없음.

**jarvis-ai** — I-17 무변경. I-9·I-11·I-12는 에러 어휘와 목록 내용이 바뀌므로 공유 필요.

## 6. 남은 리스크

**`ALREADY_HIDDEN` 잔존.** I-12에서 더 이상 쓰이지 않지만 노션에 어휘가 남아 있다. 노션 개정 시
`ALREADY_DELETED`로 정정하고, 코드에서는 사용처가 사라지면 제거한다.

**`SellerProductCreateRequest`·`SellerProductUpdateRequest`의 `status`가 `ProductStatus` 타입이라
`DELETED`가 바인딩된다.** ⑥⑦에서 서비스 계층으로 막는다 — 타입에서 막으려면 별도 enum이 필요한데
그 비용이 더 크다.
