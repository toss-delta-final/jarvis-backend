# 시드 재기준화 — sample-100 일원화 (design)

- 날짜: 2026-07-23
- 브랜치: `refactor/seed-rebase-sample100`
- 배경: 기존 `seed-phase2.sql`(가상 상품 50)을 걷어내고 **sample-100(11번가 실물 100건)**을
  카탈로그 정본으로 삼는다. phase6의 주문·로그·판매자 소유권을 실물 product_id로 재작성한다.

## 1. 파일 구조 (목적별 재분할)

**삭제**: `seed-phase1.sql`, `seed-phase2.sql`, `seed-phase4.sql`, `seed-phase6.sql`

**생성** (적용 순서 = 파일 접두 순):

| 파일 | 내용 | 유래 |
|---|---|---|
| `seed-accounts.sql` | 판매자(seller@, seller2@) + 구매자(buyer1~5@) | phase1 + phase6 계정부 |
| `seed-catalog.sql` | sample-100 (카테고리+브랜드+상품100+옵션) | 생성기 산출물(외부 제공) |
| `seed-commerce-demo.sql` | ①seller2 브랜드 소유권 ②주문·아이템·상태로그 ③문의 | phase6 거래부 + phase4 |
| `seed-analytics-demo.sql` | behavior_events·product_change_logs·account_event_logs | phase6 로그부 |

적용 순서: **accounts → catalog → commerce-demo → analytics-demo**
(members·products가 먼저 있어야 소유권·주문·로그가 참조 가능)

## 2. seller2 상품 라인업 (데모 리셀러 브랜드, 가전/디지털 7종)

**스키마 제약**: `uk_brand_seller`(brand.seller_id UNIQUE) → **판매자당 브랜드 1개**.
sample-100은 브랜드당 상품 ~1개(최대 LG전자 3개)라, 실브랜드 통째 소유로는 대시보드가 최대 3종.
→ **데모 리셀러 브랜드 `자비스일렉트로닉스`(seller2 소유)를 생성하고 가전/디지털 7종의 `product.brand_id`를 편입**한다.
7종(원 제조사 표기는 상품명에 남고 brand만 판매자 브랜드로 바뀜 — 리셀러):
2376177306 더함 32"TV / 9233450819 LG 32U631A / 9236338700 MSI / 9349985278 삼성 55"TV(옵션 '단일옵션') /
9381716646 LG 27U411A / 9434825492 LG 27U411A / 9470646527 삼성 G5. (편입된 원 브랜드는 상품 0개로 남음 — 무해)

## 3. 재기준화 원칙 — "데모 shape 보존, id만 실물로"

- **주문/아이템**: 위 7개의 실물 id·실제 상품명·실제 가격으로 재작성. 최근 30일 분산,
  I-6 이상탐지 스파이크 주문 1건(고액 삼성 TV), 반품 1·취소 1 유지. `orders.total = Σ(item price×qty)`.
- **product_change_logs**: 실물 id로 PRICE / STOCK '0'(품절 신호) / STATUS HIDDEN 신호 유지.
- **account_event_logs**: buyer3~5 이탈(45일+), 동일 IP 연속 LOGIN_FAIL(무차별 대입) 그대로.
- **behavior_events**: 상품 id 계산(`37 + seq MOD 9`)을 7개 실물 id 배열 선택(`ELT`)으로 교체.
  product_view/add_to_cart/checkout_start/purchase_complete/search 분포·건수 유지.
- **문의**: `user@jarvis.shop`(가입 API 생성) 대상 유지 — 없으면 skip(기존 동작 보존).

## 4. 배선

- `DEPLOY.md` §4: 시드 순서를 새 4파일로 교체.
- `scripts/setup-frontend-dev.sh`: 시드 적용 루프를 새 4파일로 교체.
- `scripts/README.md`: 시드 표 갱신.

## 5. 주의 / 커플링 / 팔로우업

- commerce/analytics가 sample-100 실물 id를 하드코딩 → 원본이 바뀌어 그 7개가 사라지면 깨짐.
  선택 id·브랜드명은 각 파일 상단 주석에 명시.
- 생성기(`generate-sample-100-sql.py`)·원본(`sample_100`)이 repo에 없으면 catalog 재생성 불가.
  이번 범위 밖 — **팔로우업**으로 repo 편입 여부 결정.
- 재실행 무해 원칙 유지(`INSERT IGNORE` / `NOT EXISTS` / `ON DUPLICATE KEY UPDATE`).
