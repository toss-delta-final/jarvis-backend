# 06. 구현 순서 계획

> 각 단계는 "완료 조건(검증 방법)"이 충족돼야 다음으로 넘어간다. 구현 세션은 단계마다 브랜치를 따고(feature-workflow), 끝나면 ship-it으로 PR을 만든다. 한 단계 = PR 1개가 기본.

## 진행 원칙

- **명세가 원본**: 01~05 문서와 코드가 어긋나면 문서를 먼저 고치는 PR을 낸다(코드에 몰래 맞추지 않는다).
- **세로로 얇게**: 도메인 하나를 Controller→Service→Repository→테스트까지 관통해서 끝내고 다음 도메인으로. 전 도메인의 엔티티만 먼저 깔아두는 식(가로 슬라이스) 금지 — 통합 검증이 늦어짐.
- **완료 조건은 실행 가능해야 함**: "코드 작성함"이 아니라 "curl로 이런 응답 확인".

## Phase 0. 스캐폴딩 (0.5일)

Spring Boot 3.5 + Gradle 프로젝트 생성(`backend/`), docker-compose(MariaDB+Redis), 03 문서의 global 패키지(envelope, ErrorCode, GlobalExceptionHandler, Async/Scheduling 설정), application.yml 프로파일 구조.
- **완료**: `./gradlew bootRun`(JAVA_HOME 명시) 후 `GET /actuator/health` 200. 존재하지 않는 경로가 envelope 형식의 404를 반환.

## Phase 1. 인증 + 회원 (1일)

member/guest/refresh_token 테이블, 일반 가입/로그인/로그아웃/refresh/me(A-1~A-5), JWT 필터, Role 가드, 게스트 쿠키 발급. (OAuth는 MVP 제외 — 2026-07-07 팀 결정)
- **완료**: 가입→로그인→AT로 /me→만료 후 refresh→로그아웃 시나리오가 curl로 통과. SELLER 시드 계정으로 USER 전용 API 403 확인.

## Phase 2. 카탈로그 (1일)

category/brand/product(+option — 재고 100 초기화, 02 D33), P-1/P-2/P-3(빈 목록)/P-4/P-6, **이벤트 수집 API(E-1 `POST /api/events`) + behavior_events 적재**(02 D31 — 서버측 조회 적재 없음, FE가 전송). **시드 데이터 1차분**(대분류 4개+소분류 12개, 상품 50개 수준 — LLM팀 협의 전 개발용 최소치).
- **완료**: 상세 조회가 이미지/옵션/평점(0건)을 포함해 응답. E-1로 product_view 배치 전송 시 behavior_events 행 증가·중복 UUID 무시 확인.

## Phase 3. 장바구니 + 주문 + 클레임 (1.5일)

cart(C-1~4 — 게스트 담기 + 가입 시 병합 승계, 02 D30), orders/order_item 스냅샷 생성 + mock 결제(O-1, O-2 — 아이템 PENDING→ORDERED 전이, 01 D9), 주문 조회(O-3, O-4 가능 액션 포함), 배송 전이 + 클레임 자동 승인 스케줄러(01 §6), 클레임 신청/내역(O-5, O-6 — 취소/반품만, 01 D11), **OrderStatusChanger + order_status_logs 기록(01 D12·§6.5) + 결제 성공 시 재고 차감(02 D33)**.
- **완료**: 담기→주문(성공/실패 수단 각각, 실패 주문 아이템이 PENDING으로 남는지 포함)→간격 1분으로 줄인 스케줄러로 DELIVERED 도달→반품 신청→자동 승인→상태·내역 반영까지 e2e 시나리오 통과. **바로 구매(items[] 경로)도 장바구니 미접촉으로 주문됨 확인**. 01 문서 §7 체크리스트 전부 확인.

## Phase 4. 마이페이지 잔여 (1일)

review(M-1·M-3 — **M-2는 MVP 제외**, FE 화면 없음 07-17) + P-3 실데이터(별점 분포 distribution 포함), wishlist(M-4~6), recent(M-7), address(M-8), inquiry 조회(M-9). (**M-10 프로필 수정도 MVP 제외** — 07-17) (관리자 AD-1~7은 MVP 제외 — 2026-07-09 팀 결정, 04 §9)
- **완료**: 후기 자격 상태(DELIVERED/CONFIRMED — 교환 제거, 01 D11)에서만 작성됨(그 외 400), 후기 신고 접수·중복 신고 409 확인. 신고 처리(HIDE)·문의 답변은 고도화 — 데모용 답변 완료 문의는 시드로.

## Phase 5. 채팅 티켓 발급 + 카드 하이드레이션 + internal API (1.5일, LLM팀 병행 필요)

세션 발급(Redis TTL), **CH-1 세션+스트림 티켓(RS256) 발급 + JWKS 엔드포인트(`/.well-known/jwks.json`)**, **추천 목록 콜백(I-21) + 목록 조회(CH-5) — 스키마 확정 전엔 P-7로 임시**, CH-1b 티켓 재발급, 게스트 쿠키 발급, internal(04 §10 중 확정분 — I-13·I-17·I-21 OPEN은 스텁) + 서비스 토큰 필터. 채팅 SSE는 FastAPI 직결이라 **Spring은 SSE를 중계하지 않는다**(03 D5) — BE는 티켓 발급·검증키·콜백만 책임. FastAPI가 아직 없으면 **mock FastAPI**(고정 SSE 반환·티켓 JWKS 검증 스텁)로 FE 직결 흐름을 먼저 검증.
- **완료**: CH-1이 유효 티켓 발급 → (mock)FastAPI가 JWKS로 검증 → I-21 콜백 저장 → SSE `products.ready{listId}` 수신 → FE가 CH-5(확정 전 P-7)로 카드 조립. internal API가 토큰 없이 401, FE 경로로 접근 불가.
- **선행 조건**: 05 계약 v0.3 핵심(직결·티켓·2왕복·목록 콜백)은 합의/제안 상태(2026-07-17). 잔여 OPEN(05 §4)은 스텁으로 진행.
- **(2026-07-18 구현 결정) 판매자 콜백(I-6~I-16)은 Phase 6으로 이관**: S-1~S-3와 서비스·검증을 공유하도록 명세돼 있어(04 — S-3=I-9 동일 서비스, 상품수정은 I-11이 소관) Phase 5에서 먼저 만들면 중복 후 재통합이 된다. Phase 5의 internal 범위는 소비자·추천 축(I-1~I-5·I-18·I-19·I-20·I-21) + I-17 스텁(501 `NOT_IMPLEMENTED`). I-13도 판매자 그룹과 함께 Phase 6으로 — **당시엔 명세 미확정이라 스텁 예정이었으나, 노션 본문 확정(2026-07-18)으로 Phase 6에서 실구현 완료**.
- **(2026-07-18 구현 확정)** CH-5는 05 제안 스키마대로 임시 구현(`{listId, items[카드]}` — 순서 보존·HIDDEN/품절 드롭), I-21은 `{sessionId, listId, productIds[]}`(UUID 필수·≤20·Redis TTL 10분). I-20 통지는 로그아웃·새 대화 트리거만 — 30분 유휴는 Redis TTL 자연 소멸이라 통지 없음(FastAPI 자체 TTL이 백스톱, 05 §3).

## Phase 6. 판매자 + 시드 완성 + 통합 (1일)

S-1~S-3(판매자 직접 경로는 조회만 — 구 S-5 직접수정 폐기 2026-07-21) + **판매자 internal 콜백(I-6~I-16 — Phase 5에서 이관, I-13 포함 전건 실구현)**, 시드 데이터 최종분(02 §5 규모, LLM팀 합의 형식 — 재고 100), behavior_events·로그 테이블 더미 생성 스크립트, 전 구간 통합 점검.
- **완료**: 판매자 계정으로 summary가 0이 아닌 지표 반환. 대표 데모 시나리오(추천→담아줘→주문→반품→자동 승인 확인→문의 챗봇) 리허설 1회 통과.
- **(2026-07-18 구현 확정)** I-6~I-16 응답 스키마는 05 §I-6b(BE 확정 — LLM 합의 대기). change log는 ENUM 3종(PRICE=판매가/STOCK/STATUS)만 기록, 그 외 필드는 I-11 응답 changes[]로만. S-4 티켓 claim에 `channel:SELLER`+`brand_id` 추가(CH-1로는 SELLER 발급 불가, CH-1b 재발급도 유지). 시드 최종분 중 크롤링 1만+ 적재는 LLM팀 파이프라인 소관 — BE는 `scripts/seed-phase6.sql`(판매자 2호·구매자 5·주문 9건·로그 3종·behavior_events 570여 건, 날짜는 NOW() 상대값)로 대시보드 데모를 보장.

## 일정 감각

합계 ~7.5일. MVP까지 남은 기간과 맞물리므로 **Phase 3까지가 첫 주 목표**, Phase 5는 LLM 팀 진도와 동기화. 지연 시 자르는 순서(뒤에서부터): S-4 판매자 챗봇 → 연관 추천. (관리자 기능은 이미 MVP 제외라 자를 목록에 없음)

## 구현 세션(Claude)에게

- 시작 전에 이 폴더의 01~05를 전부 읽어라. CLAUDE.md의 빌드 규칙(JAVA_HOME 명시)을 지켜라.
- 각 Phase 완료 조건을 실제로 실행해 확인하기 전에는 완료라고 보고하지 마라.
- 명세에 없는 판단이 필요해지면: 작다면 이 문서들의 결정 로그 스타일로 문서에 추가하고 진행, 크다면(스키마 변경·계약 변경) 사용자에게 물어라.
