# 상품 상세 이미지 (product_detail_image) — design

- 날짜: 2026-08-05
- 브랜치: `docs/product-detail-images-spec`
- 배경: 상품 상세페이지 하단에 상세 이미지를 순서대로 나열한다(쿠팡식). 현재는 대표 이미지 1장뿐이며,
  이는 **02 D14에서 의도적으로 내린 결정**이었다. 기각 사유가 "시드·크롤링 비용"이었는데
  배포 담당이 그 비용을 이미 치러 **파일 55,887장이 S3에 적재 완료**된 상태다 — 전제가 바뀌었으므로
  D14를 뒤집는다. D14가 미리 적어둔 되돌림 경로(`product_image` 1:N 재도입)를 따른다.

## 0. 전제 — 배포 담당이 제공한 실측

| 항목 | 값 |
|---|---|
| 버킷 | `jarvis-storage-049705857330-ap-northeast-2-an` (ap-northeast-2), **`products/*` 만 공개 정책** |
| 경로 | `products/{productId}/detail/000.jpg` — `{productId}`는 `product.id` 그대로(11번가 원본 ID, 별도 매핑 없음) |
| 이미지 | 55,887장 |
| 상세 이미지가 있는 상품 | 6,428건 |
| 상세 이미지가 **없는** 상품 | 131건 |
| 상품당 장수 | 최소 1 · 중앙값 6 · 최대 179 (20장 초과 524건, 50장 초과 45건, 100장 초과 3건) |
| 확장자 | jpg 44,457 · png 7,805 · gif 2,722 · jpeg 639 · webp 264 — **상품 내에서도 섞임** |
| key 최장 | 35자 |
| 장당 평균 용량 | 810KB |

`product.id`가 11번가 원본 ID인 것은 [`scripts/seed-catalog.sql`](../../../scripts/seed-catalog.sql)에서 확인했다
(`INSERT INTO product (id, ...) VALUES (774617209, ...)` — AUTO_INCREMENT 자리에 원본 ID를 직접 지정).

**무거운 상품 3건(수동 확인용)**: `9276734652`(179장) · `792829510`(151장) · `9458997118`(107장)

## 1. 스키마

```sql
CREATE TABLE product_detail_image (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    product_id  BIGINT       NOT NULL,
    sort_order  INT          NOT NULL,               -- 0-based 노출 순서. 파일명이 아니라 이 값이 정렬 기준 (D42)
    image_key   VARCHAR(255) NOT NULL,               -- 버킷 내 경로만: products/{productId}/detail/000.jpg — 호스트는 앱 설정 (D42)
    created_at  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_detail_image (product_id, sort_order),
    CONSTRAINT fk_product_detail_image_product FOREIGN KEY (product_id)
        REFERENCES product (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

골격은 [`wishlist`](../../backend/schema.sql)와 동일(대리키 + UNIQUE + `created_at`만). 갈린 지점만 근거를 남긴다.

**`sort_order`를 따로 두는 이유 — 확장자 혼재.** 파일명이 `000`·`001`로 순서를 담고 있지만 확장자가
상품 내에서도 섞여(`000.jpg` + `001.png`) **문자열 정렬이 어긋난다.** 순서를 정수 컬럼으로 분리하면
`image_key`는 파싱 대상이 아닌 불투명한 문자열이 되어, 파일명 규칙이 바뀌어도 정렬이 깨지지 않는다.
조회는 항상 `ORDER BY sort_order`이며 값이 연속일 필요는 없다.

**전체 URL이 아니라 key만 저장.** 이미지가 Cloudflare 뒤로 이전될 예정이라, URL을 통째로 넣으면
그 시점에 55,887행을 UPDATE해야 한다. 호스트는 앱 설정으로 붙인다(§2).

**`product.image_url`은 전체 URL인데 이 컬럼만 key인 것은 의도.** 대표 이미지는 현재 11번가 CDN에 있는
외부 자원(`https://cdn.011st.com/...`)이고 상세 이미지는 우리 S3 자원이라 **실제로 호스트가 다르다.**
형태를 억지로 맞추면 사실과 어긋난다. 컬럼명을 `image_url`이 아닌 `image_key`로 둔 것도 같은 이유다.
대표 이미지를 S3로 이전하는 건 별개 작업이며, 그때 `image_url`을 같은 방식으로 맞춘다.

**`ON DELETE RESTRICT`** — repo 전체 컨벤션을 따른다. CASCADE를 검토했으나 채택하지 않았다:
① 상품 하드 삭제 경로가 없다([`SellerProductService.softDelete`](../../../src/main/java/com/jarvis/seller/SellerProductService.java) — *"soft delete(HIDDEN 전환)만, hard delete 문 없음"*, 시드도 업서트)
② **DB CASCADE는 S3까지 가지 않는다** — DB 행만 조용히 지워지고 파일은 버킷에 남아, 정리가 다 된 것처럼 보인다
③ 실제 하드 삭제가 일어나는 유일한 경로(배포 담당의 시드 정리)는 이미 이 테이블을 명시적으로 비운다.
되돌리려면 `ALTER TABLE` 한 줄이고 데이터 마이그레이션은 없다.

**`updated_at` 없음** — 개별 행이 갱신되는 개념이 없다. 갱신은 전량 재적재 형태다.

**인덱스는 UNIQUE 하나로 충분** — 선두 컬럼이 `product_id`라 조회 인덱스를 겸한다. 유일한 쿼리가
"상품 1건 → 순서대로 전부"이기 때문이다. 덤으로 **같은 상품에 순서가 중복 적재되는 사고를 DB가 막는다**
(어긋나면 이미지 순서가 조용히 틀어지는데, 이런 건 사람이 눈치채지 못한다).

**이미지가 없는 131건은 행을 만들지 않는다.** NULL 행 같은 것을 두지 않고 응답에서 빈 배열로 나간다.

크기: 55,887행 × 60바이트 남짓 ≈ **4MB**.

## 2. 설정 — 호스트 주입

`application.yml`의 `app` 블록에 추가한다.

```yaml
  # 상품 이미지 호스트 (S3 → Cloudflare 이전 예정이라 DB엔 key만 저장, 앞부분은 여기서 붙인다)
  image:
    base-url: ${APP_IMAGE_BASE_URL:https://jarvis-storage-049705857330-ap-northeast-2-an.s3.ap-northeast-2.amazonaws.com}
```

**시크릿이 아니므로 기본값을 yml에 둔다.** 공개 버킷 URL이라 커밋해도 되고, 로컬은 무설정으로 동작한다.
배포는 `APP_IMAGE_BASE_URL`로 덮어쓰며, Cloudflare 이전 시 이 환경변수만 바꾸면 DB는 건드리지 않는다.
`app.llm.base-url`이 `${LLM_BASE_URL:}`로 빈 기본값을 쓰는 것과 달리, 빈 값이면 이미지가 전부 깨지므로
기본값을 실제 값으로 둔다.

바인딩은 [`LlmProperties`](../../../src/main/java/com/jarvis/chat/LlmProperties.java)와 같은 record 방식:

```java
@ConfigurationProperties(prefix = "app.image")
public record ImageProperties(String baseUrl) {
}
```

**끝의 슬래시는 조립 지점에서 한 번 정규화한다** — base-url이 `/`로 끝나는지에 따라 `//detail`이 되거나
경로가 붙어버리는 것은 이런 설정에서 가장 흔한 사고다. 조립을 한 곳으로 모아 처리도 한 번만 한다.

## 3. API — P-2 응답

[`ProductDetailResponse`](../../../src/main/java/com/jarvis/product/dto/ProductDetailResponse.java)에
`List<String> detailImages`를 추가한다. 값은 **호스트가 붙은 전체 URL 배열**이다.

```json
"imageUrl": "https://cdn.011st.com/.../B.jpg",
"detailImages": [
  "https://.../products/9276734652/detail/000.jpg",
  "https://.../products/9276734652/detail/001.png"
]
```

**문자열 배열이며 객체로 감싸지 않는다** — 순서가 배열 순서로 이미 표현되므로 `{url, order}` 형태는
같은 정보를 두 번 담는다. 이미지가 없으면 `[]`.

**대표 이미지는 이 배열에 포함하지 않는다** — 상단 대표 1장과 하단 상세 N장은 화면에서 역할이 다르다.
`imageUrl`과 `detailImages`를 분리해 두면 FE가 둘을 합치든 나누든 받아준다.

**전량 반환하며 서버에서 자르지 않는다.** 최대 179장이어도 URL 배열은 **약 22KB**(2026-08-05 실측). 실제 부하는 이미지
바이트(장당 평균 810KB)이며 이는 반환 개수가 아니라 브라우저의 지연 로딩으로 다루는 문제다.
화면에서 접기·`loading="lazy"`는 FE 소관이며 BE 스펙에는 "전량 반환"만 남긴다.

**캐싱하지 않는다** — 상품 1건에 인덱스를 탄 쿼리 하나라 캐시가 벌어줄 것이 없다.

## 4. 구현 방침 — JPA 연관관계를 매핑하지 않는다

`Product` 엔티티에 `@OneToMany List<ProductDetailImage>`를 **걸지 않는다.** 상세 조회 경로에서만
별도 Repository 쿼리(`findByProductIdOrderBySortOrder`)로 가져온다.

이유: 상품을 **여러 건** 조회하는 경로(P-4 인기 상품, P-6 목록, CH-5 챗봇 카드, 검색)에서 누군가
컬렉션을 건드리면 N+1이 발생한다. 화면에 상세 이미지가 나오지도 않는 경로에서 상품 수만큼 추가
쿼리가 나가는 형태이며, LAZY여도 마찬가지고 코드 리뷰에서 잘 잡히지 않는다. **매핑 자체를 두지 않으면
접근 경로가 없어 사고가 원천적으로 발생하지 않는다.**

이는 §6에서 JSON 컬럼을 기각한 근거("상품을 읽는 대부분의 순간에 필요 없다")와 같은 논리다.

## 5. 시드 · 로컬 개발

**실 데이터**는 배포 담당이 매니페스트로 INSERT SQL을 생성해 배포 DB에 적재한다. 시드를 재적재할 때는
이 테이블도 함께 비우고 다시 넣는다. **55,887행은 repo에 커밋하지 않는다.**

**로컬 개발용 샘플**은 개인 파일로 두고 커밋하지 않는다.

- `.gitignore`에 `scripts/seed-*-local.sql` 추가
- `setup-frontend-dev.sh`에 "없으면 skip" 루프 추가 — 마이그레이션 루프의 `[ -e "$f" ] || continue` 패턴 재사용
- 파일 자체가 repo에 없으므로 `DEPLOY.md`의 시드 목록을 건드릴 필요가 없고, 배포 DB에 흘러들 경로가 존재하지 않는다

**혼동 주의**: 배포에 반영되어야 하는 것은 `schema.sql`과 `migrate-2026-08-05-product-detail-image.sql`이고,
가지 말아야 하는 것은 `seed-*-local.sql`이다.

[`scripts/README.md`](../../../scripts/README.md) 규칙에 따라 **파일이 두 개** 필요하다 —
`schema.sql`은 최초 생성 전용(재실행 불가)이므로 이미 스키마가 깔린 DB에는 `migrate-*.sql`을 별도로 적용한다.

## 6. 기각한 대안

**`product`에 JSON 컬럼(`detail_images`) 추가** — `product`는 서비스에서 가장 자주 읽히는 테이블인데
상세 이미지를 쓰는 곳은 상세페이지뿐이다. 최대 179장(약 6KB)을 상세 이미지가 필요 없는 모든 조회가
행에 달고 다니게 된다. JPA는 기본적으로 엔티티의 모든 컬럼을 읽고, 컬럼 단위 지연 로딩
(`@Basic(fetch=LAZY)`)은 바이트코드 조작이 필요해 실효성이 낮다. 또한 장수 편차가 179배(1~179)라
행으로 펴는 것이 맞다 — [`recommendation_list_item`](../../backend/schema.sql)에서 같은 판단의 전례가 있다.
`product.attributes`가 JSON인 것과는 성격이 다르다: attributes는 D7 2단 검색의 필터 축이라
**상품을 읽을 때 실제로 필요한 데이터**인 반면, 상세 이미지는 목록에서 쓸 일이 없다.

**S3에 상품별 `manifest.json`을 두고 FE가 직접 읽기** — BE 계약을 바꾸지 않아도 되고 목록이 이미지와
같은 곳에 살아 정합성이 공짜라는 장점이 뚜렷했다. 기각 사유: ① "FE가 S3 경로 규칙을 안다"는 **암묵 계약**이
어디에도 문서화되지 않고 별도 repo에 흩어진다 — 계약이 없어지는 게 아니라 보이지 않는 곳으로 숨는다
② 이미지 없는 131건의 404와 실제 장애를 FE가 구분할 수 없다(빈 manifest를 131건에도 올려야 해소)
③ `fetch()`로 JSON을 읽으므로 **CORS 설정이 필요**해진다(`<img>`는 불필요) ④ 본문은 떴는데 이미지 영역만
실패한 상태가 생긴다. 우리 데이터는 한 번 적재하고 끝나는 정적 시드라 정합성이 어긋날 기회가
재적재 시점 몇 번뿐이며, 그건 절차로 막을 수 있다.

**BE가 S3 manifest를 읽어 P-2에 실어주기** — 진실은 S3 한 곳이고 계약도 명세서에 남는 절충안이지만,
P-2에 아웃바운드 호출이 붙어 캐시가 필수가 된다. [07-redis-design](../../backend/07-redis-design.md)
구현이 전량 미착수인 현시점에서 비용이 가장 크다.

## 7. 영향 범위

| 구분 | 대상 |
|---|---|
| **ERD (불변 기준)** | `docs/backend/schema.sql` — 테이블 추가 + §7 주석의 "상품 이미지 테이블(D14 — image_url 단일)" 줄 |
| **노션 (불변 기준)** | 📡 API 명세서 **P-2** 응답에 `detailImages`, 「기능 정의」의 "상품 이미지(단일)" |
| 내부 문서 | 02 — **D42 신설** + D14에 "2026-08-05 재도입" 주석, ERD 다이어그램, §4 화면별 표(4 상품 상세), §7 / 04 — P-2 행 |
| 코드 | `ProductDetailResponse`(필드 + `from` 시그니처 + 클래스 주석의 D14 인용), `ProductService`, 신규 `ProductDetailImage`·Repository·`ImageProperties`, `application.yml` |
| 시드·인프라 | `scripts/migrate-2026-08-05-product-detail-image.sql`, `.gitignore`, `scripts/setup-frontend-dev.sh` |
| 테스트 | 0장 / 1장 / 179장, `sort_order` 정렬, base-url 슬래시 정규화 |

**jarvis-frontend — 영향 있음.** `src/features/product/types.ts`의 `ProductDetail`에 `detailImages` 추가가
필요하다. 또한 `src/features/product/index.tsx`에 **D14를 근거로 단 주석이 낡는다**:

```ts
// 갤러리는 대표 이미지 단일(02 D14)이라 상세 이미지 1장만 사용.
const images = [view.imageUrl];
```

이 코드는 상단 갤러리이고 쿠팡식 하단 나열은 별도 섹션이므로, `imageUrl`/`detailImages` 분리(§3)가
FE의 어느 선택이든 수용한다.

**jarvis-ai — 영향 없음.** `imageUrl`을 쓰지만 전부 `/internal/*` 경로이며 P-2를 소비하지 않는다.
필드 추가는 하위 호환이다.

## 8. Phase 계획

| Phase | 내용 |
|---|---|
| **0** | **상품 상태 설계(별건, §9)** — 상세 이미지와 무관하나 먼저 진행하기로 함 |
| **1** | 스키마·설정: `schema.sql` + `migrate-*.sql`, `ImageProperties` + `application.yml`, 02 D42 신설, `.gitignore`·`setup-frontend-dev.sh` |
| **2** | 조회 구현: 엔티티·Repository·Service, P-2에 `detailImages`, 04 P-2 갱신, 테스트 |
| **3** | 배포 담당 실 데이터 적재 (배포 DB) |

**Phase 1 선행 조건 — 노션 개정.** ERD와 노션 API 명세서는 CLAUDE.md Contract hierarchy상 불변 기준이라
임의로 고칠 수 없다. **노션 📡 API 명세서 P-2와 「기능 정의」가 먼저 개정되어야** repo 문서와 코드가
따라갈 수 있다. 이 문서는 그 개정의 근거이지 개정 자체가 아니다.

## 9. 후속 — Phase 0 (별건)

설계 중 발견한 별개 구멍이다. **상세 이미지와 데이터·코드가 겹치지 않으므로 따로 다룬다.**

현재 `product.status`는 `ON_SALE` / `HIDDEN` 2종인데, `HIDDEN`에 도달하는 경로가 둘이며 서로 구분되지 않는다.

- **I-11** 상품 수정(상태 변경) → `HIDDEN` — "숨김/판매정지"
- **I-12** 삭제 → `HIDDEN` — "삭제"

[S-3 판매자 목록은 `HIDDEN`을 노출](../../backend/04-api-spec.md)하므로(*"HIDDEN도 노출(본인 화면)"*),
**판매자가 삭제한 상품이 자기 목록에 계속 남고 숨긴 상품과 구분되지 않는다.** 또한
[`SellerProductService`](../../../src/main/java/com/jarvis/seller/SellerProductService.java)가 이미 `HIDDEN`이면
409 `ALREADY_HIDDEN`을 던지므로 **숨김 처리해둔 상품은 삭제 자체가 불가능하다** — "숨겼다가 나중에 지운다"는
평범한 흐름이 막혀 있다.

빠진 축은 가시성이다:

| 상태 | 소비자 | 판매자 |
|---|---|---|
| ON_SALE | 보임 | 보임 |
| HIDDEN (숨김·판매정지) | 안 보임 | **보임** |
| 삭제됨 | 안 보임 | **안 보임** |

세 번째 줄을 표현할 값이 없다. 건드리는 범위가 `product.status` 값·I-11·I-12 동작·S-3 탭과 `tabCounts`·
삭제 상품에 대한 P-2 응답·노션 명세로, 상세 이미지보다 넓다.

**같은 날(2026-08-05) 머지된 `PurchaseState`를 먼저 읽고 시작할 것** — *"purchasable을 purchaseState로 교체
— 품절과 숨김을 구분"*. 소비자 쪽에서 품절/숨김을 구분하는 파생 상태를 이미 도입했으므로, Phase 0은
그 위에 **판매자 쪽 가시성(숨김 vs 삭제됨)** 을 얹는 형태가 된다. 두 축(소비자 구매 가능 여부 /
판매자 목록 노출 여부)을 하나의 값으로 합치려 들면 지금과 같은 문제가 반복된다.
