-- ============================================================
-- DB Schema (MariaDB 11.x / InnoDB / utf8mb4) — 공유용 DDL 스냅샷
-- 원본(source of truth): docs/backend/02-data-model.md
--   테이블 정의·결정 근거(D1~D36)는 02 문서를 따른다.
--   02가 바뀌면 이 파일도 함께 갱신할 것 (drift 금지).
-- 표기: Dn = 02 문서 결정 로그 번호.
-- 공통: PK는 id BIGINT AUTO_INCREMENT (guest만 UUID CHAR(36)).
--       created_at 전 테이블, updated_at은 변경이 있는 테이블만.
--       FK ON DELETE는 전부 RESTRICT (운영 데이터 보호).
-- FK가 못 막는 교차 정합(D26)은 서비스 레이어 검증 — 각 테이블 주석 참조.
-- MariaDB 주의: JSON 타입은 LONGTEXT 별칭(+ 자동 CHECK(JSON_VALID)) — 네이티브 저장 아님.
--   Hibernate는 MariaDBDialect + @JdbcTypeCode(SqlTypes.JSON)로 매핑(02 §3 매핑 규약).
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 회원 / 인증
-- ------------------------------------------------------------

CREATE TABLE member (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    email              VARCHAR(255) NOT NULL,
    password           VARCHAR(255) NOT NULL,              -- BCrypt
    nickname           VARCHAR(50)  NOT NULL,
    role               VARCHAR(20)  NOT NULL,              -- USER / SELLER / ADMIN (SELLER·ADMIN은 시드 전용)
    gender             VARCHAR(10)  NOT NULL,              -- MALE / FEMALE (D21)
    birth_date         DATE         NOT NULL,              -- 가입 시 수집 (D16)
    agreed_terms_at    DATETIME     NOT NULL,              -- 이용약관 동의 시각 (D21)
    agreed_privacy_at  DATETIME     NOT NULL,              -- 개인정보처리방침 동의 시각 (D21)
    created_at         DATETIME     NOT NULL,
    updated_at         DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE guest (
    id                   CHAR(36) NOT NULL,                -- UUID, 쿠키 값 그대로 (D5)
    converted_member_id  BIGINT   NULL,                    -- 가입/로그인 승계 시 기록 (D5)
    created_at           DATETIME NOT NULL,
    updated_at           DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_guest_converted_member FOREIGN KEY (converted_member_id)
        REFERENCES member (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE refresh_token (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    member_id   BIGINT   NOT NULL,
    token_hash  CHAR(64) NOT NULL,                         -- SHA-256 hex, 원문 저장 금지 (D17)
    expires_at  DATETIME NOT NULL,
    created_at  DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_token_hash (token_hash),
    CONSTRAINT fk_refresh_token_member FOREIGN KEY (member_id)
        REFERENCES member (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------
-- 상품 도메인
-- ------------------------------------------------------------

CREATE TABLE brand (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    seller_id    BIGINT       NULL,                        -- 판매자 1명 = 브랜드 1개. NULL = 크롤링 적재 브랜드 (D25)
    name         VARCHAR(100) NOT NULL,
    logo_url     VARCHAR(500) NULL,
    description  TEXT         NULL,
    created_at   DATETIME     NOT NULL,
    updated_at   DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_brand_seller (seller_id),                -- UNIQUE는 NULL 다중 허용 → 제약 유지 (D25)
    UNIQUE KEY uk_brand_name (name),
    CONSTRAINT fk_brand_seller FOREIGN KEY (seller_id)
        REFERENCES member (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE category (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    parent_id         BIGINT      NULL,                    -- NULL=대분류(메인 해시태그), 값 있음=소분류 — 2단 (D20)
    name              VARCHAR(50) NOT NULL,                -- 전역 UNIQUE — 동명 소분류는 시드 명명으로 회피 (D27)
    attribute_schema  JSON        NULL,                    -- 소분류 전용 속성 축(키 배열, 예: ["소재","색상","사이즈"]) (D11)
    created_at        DATETIME    NOT NULL,
    updated_at        DATETIME    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_name (name),
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_id)
        REFERENCES category (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE product (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    brand_id          BIGINT       NOT NULL,
    category_id       BIGINT       NOT NULL,               -- 소분류(leaf)만 참조 — 서비스 검증 (D20·D26②)
    name              VARCHAR(200) NOT NULL,
    original_price    INT          NOT NULL,               -- 정가 (KRW 원 단위 정수)
    price             INT          NOT NULL,               -- 판매가 (D15). price ≤ original_price 서비스 검증 (D28). 할인율은 파생 계산
    stock_quantity    INT          NOT NULL DEFAULT 0,     -- 재고 (D33 — D8 폐기). 시드 초기값 일괄 100. 결제 성공(PAID)과 같은 트랜잭션에서 조건부 UPDATE 차감·부족 시 결제 실패, 0 도달 시 STOCK 로그 1행 (D32). 복원 MVP 미구현
    image_url         VARCHAR(500) NOT NULL,               -- 대표 이미지 1장 — 단일 확정 (D14)
    base_sales_count  INT          NOT NULL DEFAULT 0,     -- 크롤링 시점 누적 판매량, 시드 후 불변 (D18). 표시 판매량 = 이 값 + order_item 집계
    summary           VARCHAR(500) NULL,                   -- 주요 특징 요약
    attributes        JSON         NULL,                   -- 축은 category.attribute_schema, 값은 자유 텍스트 (D7·D11)
    description       TEXT         NULL,
    status            VARCHAR(20)  NOT NULL,               -- ON_SALE / HIDDEN
    created_at        DATETIME     NOT NULL,
    updated_at        DATETIME     NOT NULL,               -- 예외적 NOT NULL — 생성 시 created_at과 동일 값 초기화, I-17 증분 커서 (D33)
    PRIMARY KEY (id),
    KEY idx_product_category (category_id),
    KEY idx_product_brand (brand_id),
    KEY idx_product_updated (updated_at, id),              -- AI 상품 동기화 배치(I-17) 증분 커서용 (D33)
    CONSTRAINT chk_product_stock CHECK (stock_quantity >= 0),
    CONSTRAINT fk_product_brand FOREIGN KEY (brand_id)
        REFERENCES brand (id) ON DELETE RESTRICT,
    CONSTRAINT fk_product_category FOREIGN KEY (category_id)
        REFERENCES category (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- FULLTEXT 없음 — LIKE 2단 검색으로 시작 (D7). 1만+ 실데이터 적재 후 실측으로 재결정(02 §1 D7 주석).

CREATE TABLE product_option (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    product_id   BIGINT       NOT NULL,
    name         VARCHAR(100) NOT NULL,                    -- 단일 옵션 그룹: "화이트", "블랙/M" (D2)
    extra_price  INT          NOT NULL DEFAULT 0,
    created_at   DATETIME     NOT NULL,
    updated_at   DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_product_option_product (product_id),
    CONSTRAINT fk_product_option_product FOREIGN KEY (product_id)
        REFERENCES product (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------
-- 장바구니 / 배송지
-- ------------------------------------------------------------

CREATE TABLE cart_item (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    member_id   BIGINT   NULL,                             -- member/guest 중 정확히 하나 NOT NULL — XOR 서비스 검증 (D30)
    guest_id    CHAR(36) NULL,                             -- 게스트 장바구니 (D30). 로그인 시 병합 승계
    product_id  BIGINT   NOT NULL,
    option_id   BIGINT   NULL,                             -- 옵션 없는 상품은 NULL. 해당 상품 소속 옵션인지 서비스 검증 (D26①)
    quantity    INT      NOT NULL,
    created_at  DATETIME NOT NULL,
    updated_at  DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cart_member (member_id, product_id, option_id),
    UNIQUE KEY uk_cart_guest (guest_id, product_id, option_id),
    -- 주의: MariaDB UNIQUE는 NULL 중복 허용 → option_id=NULL엔 제약 미적용, 서비스의 조회-후-수량증가 upsert가 실질 방어선
    CONSTRAINT chk_cart_quantity CHECK (quantity > 0),
    CONSTRAINT fk_cart_member  FOREIGN KEY (member_id)  REFERENCES member (id)         ON DELETE RESTRICT,
    CONSTRAINT fk_cart_guest   FOREIGN KEY (guest_id)   REFERENCES guest (id)          ON DELETE RESTRICT,
    CONSTRAINT fk_cart_product FOREIGN KEY (product_id) REFERENCES product (id)        ON DELETE RESTRICT,
    CONSTRAINT fk_cart_option  FOREIGN KEY (option_id)  REFERENCES product_option (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 가격 컬럼 없음(현재가 표시 — 스냅샷은 주문 시점에만). cart 헤더 테이블 없음(주체당 암묵 1개).

CREATE TABLE address (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    member_id   BIGINT       NOT NULL,
    label       VARCHAR(50)  NOT NULL,                     -- "집", "회사"
    recipient   VARCHAR(50)  NOT NULL,
    phone       VARCHAR(20)  NOT NULL,
    zip_code    VARCHAR(10)  NOT NULL,
    address1    VARCHAR(255) NOT NULL,
    address2    VARCHAR(255) NULL,
    is_default  BOOLEAN      NOT NULL DEFAULT FALSE,       -- 회원당 1개 — 서비스 보장. 삭제 규칙은 D29
    created_at  DATETIME     NOT NULL,
    updated_at  DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_address_member (member_id),
    CONSTRAINT fk_address_member FOREIGN KEY (member_id)
        REFERENCES member (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------
-- 주문 / 클레임  (스냅샷 원칙 D1: 주문은 그 시점 사실의 기록)
-- ------------------------------------------------------------

CREATE TABLE orders (                                      -- `order`는 SQL 예약어라 복수형
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    member_id         BIGINT       NOT NULL,               -- 게스트 주문 없음 (D30 — 결제는 로그인 유도)
    status            VARCHAR(20)  NOT NULL,               -- PENDING / PAID / PAYMENT_FAILED / CANCELLED (01 문서 + D32 — 전량 취소 시 같은 트랜잭션 승격)
    payment_method    VARCHAR(30)  NOT NULL,               -- MOCK_CARD / MOCK_FAIL 등
    total_amount      INT          NOT NULL,               -- 항상 Σ(order_item.price × quantity), 서버 계산으로만 기록 (D26④). 배송비 항 없음 (D36)
    recipient         VARCHAR(50)  NOT NULL,               -- 이하 배송지 스냅샷 — address FK 아님 (D1)
    phone             VARCHAR(20)  NOT NULL,
    zip_code          VARCHAR(10)  NOT NULL,
    address1          VARCHAR(255) NOT NULL,
    address2          VARCHAR(255) NULL,
    delivery_request  VARCHAR(200) NULL,                   -- 주문 1회성 배송 요청사항 (D22)
    paid_at           DATETIME     NULL,
    created_at        DATETIME     NOT NULL,
    updated_at        DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_orders_member (member_id),
    CONSTRAINT fk_orders_member FOREIGN KEY (member_id)
        REFERENCES member (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 표시용 주문번호는 저장하지 않음 — "ORD-" + created_at(yyyyMMdd) + "-" + id 로 파생 (D24)

CREATE TABLE order_item (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    order_id           BIGINT       NOT NULL,
    product_id         BIGINT       NOT NULL,              -- 상세 이동 링크용 — 값은 아래 스냅샷 사용 (D1)
    product_name       VARCHAR(200) NOT NULL,              -- 스냅샷
    option_name        VARCHAR(100) NULL,                  -- 스냅샷
    price              INT          NOT NULL,              -- 스냅샷: product.price + extra_price
    original_price     INT          NOT NULL,              -- 스냅샷: 주문 시점 product.original_price + extra_price — 할인 표시용 (D37)
    quantity           INT          NOT NULL,
    status             VARCHAR(30)  NOT NULL,              -- 01 문서의 9개 상태 (D34 — 교환 2종 제거로 11→9)
    status_changed_at  DATETIME     NOT NULL,              -- 배송 전이 스케줄러 기준 시각
    created_at         DATETIME     NOT NULL,
    updated_at         DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_order_item_order (order_id),
    KEY idx_order_item_status (status, status_changed_at), -- 배송 전이 스케줄러 스캔용
    CONSTRAINT fk_order_item_order   FOREIGN KEY (order_id)   REFERENCES orders (id)  ON DELETE RESTRICT,
    CONSTRAINT fk_order_item_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE claim (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    order_item_id  BIGINT       NOT NULL,                  -- REQUESTED 1개 제한은 서비스 검증. 재신청 대비 1:N
    type           VARCHAR(20)  NOT NULL,                  -- CANCEL / RETURN (D34 — EXCHANGE 제거)
    status         VARCHAR(20)  NOT NULL,                  -- REQUESTED / COMPLETED / REJECTED(고도화)
    reason         VARCHAR(500) NULL,
    reject_reason  VARCHAR(500) NULL,                      -- 거절 시 필수 (고도화)
    processed_by   BIGINT       NULL,                      -- 처리 관리자 — MVP 자동 승인은 NULL (01 D10)
    processed_at   DATETIME     NULL,
    created_at     DATETIME     NOT NULL,
    updated_at     DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_claim_order_item (order_item_id),
    CONSTRAINT fk_claim_order_item FOREIGN KEY (order_item_id) REFERENCES order_item (id) ON DELETE RESTRICT,
    CONSTRAINT fk_claim_processed_by FOREIGN KEY (processed_by) REFERENCES member (id)    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------
-- 후기 / 신고 / 찜 / 문의
-- ------------------------------------------------------------

CREATE TABLE review (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    order_item_id  BIGINT      NULL,                       -- 회원 리뷰의 자격 앵커(아이템당 1개, D4). NULL = 크롤링 리뷰 (D19)
    product_id     BIGINT      NOT NULL,                   -- 목록 조회용 — body가 아니라 서버 유도로 채움 (D26③)
    member_id      BIGINT      NULL,                       -- NULL = 크롤링 리뷰 (D19). NULL이면 author_name 필수(서비스 검증)
    author_name    VARCHAR(50) NULL,                       -- 크롤링 리뷰 작성자 표시명 (D19)
    rating         TINYINT     NOT NULL,
    content        TEXT        NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'VISIBLE', -- VISIBLE / HIDDEN / DELETED — 신고 처리는 soft (D4)
    created_at     DATETIME    NOT NULL,
    updated_at     DATETIME    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_order_item (order_item_id),       -- NULL 다중 허용 → 크롤링 리뷰와 공존 (D19)
    KEY idx_review_product (product_id),
    CONSTRAINT chk_review_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT fk_review_order_item FOREIGN KEY (order_item_id) REFERENCES order_item (id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_product    FOREIGN KEY (product_id)    REFERENCES product (id)    ON DELETE RESTRICT,
    CONSTRAINT fk_review_member     FOREIGN KEY (member_id)     REFERENCES member (id)     ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 평점 평균·리뷰 수 컬럼 없음 — 조회 시 집계, 반정규화 안 함 (D9)

CREATE TABLE review_report (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    review_id     BIGINT       NOT NULL,
    reporter_id   BIGINT       NOT NULL,
    reason        VARCHAR(500) NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING', -- PENDING / IN_PROGRESS / DONE
    processed_by  BIGINT       NULL,
    processed_at  DATETIME     NULL,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_report (review_id, reporter_id),  -- 중복 신고 방지
    CONSTRAINT fk_review_report_review    FOREIGN KEY (review_id)    REFERENCES review (id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_report_reporter  FOREIGN KEY (reporter_id)  REFERENCES member (id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_report_processor FOREIGN KEY (processed_by) REFERENCES member (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wishlist (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    member_id   BIGINT   NOT NULL,
    product_id  BIGINT   NOT NULL,
    created_at  DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wishlist (member_id, product_id),
    CONSTRAINT fk_wishlist_member  FOREIGN KEY (member_id)  REFERENCES member (id)  ON DELETE RESTRICT,
    CONSTRAINT fk_wishlist_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE inquiry (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    member_id    BIGINT       NOT NULL,                    -- 접수는 로그인 사용자만
    title        VARCHAR(200) NOT NULL,                    -- LLM이 요약 생성한 제목 (D23)
    content      TEXT         NOT NULL,                    -- 챗봇이 정리한 문의 내용
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING / IN_PROGRESS / DONE
    answer       TEXT         NULL,
    answered_by  BIGINT       NULL,                        -- 답변 관리자 (고도화)
    answered_at  DATETIME     NULL,
    created_at   DATETIME     NOT NULL,
    updated_at   DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_inquiry_member (member_id),
    CONSTRAINT fk_inquiry_member   FOREIGN KEY (member_id)   REFERENCES member (id) ON DELETE RESTRICT,
    CONSTRAINT fk_inquiry_answerer FOREIGN KEY (answered_by) REFERENCES member (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------
-- 행동 이벤트 (FE 수집, user_event 대체 — D31)
-- append-only — 예외: 게스트→회원 승계 시 member_id 백필 UPDATE 1회 (D5)
-- ------------------------------------------------------------

CREATE TABLE behavior_events (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    member_id       BIGINT      NULL,                      -- 로그인 시 JWT에서 서버 주입(body 신원 무시), 비로그인 NULL. 의도적으로 FK 없음 (D31)
    guest_id        CHAR(36)    NULL,                      -- 게스트 쿠키에서 서버 주입 — 승계용 (D5 패턴 유지)
    session_key     VARCHAR(64) NOT NULL,                  -- FE SDK 생성, 30분 무활동 시 재발급
    client_event_id CHAR(36)    NULL,                      -- FE가 이벤트마다 생성하는 UUID — 중복 방지 (D35)
    event_type      VARCHAR(30) NOT NULL,                  -- 8종 화이트리스트(02 §4) — 8종 외는 수집 API가 버림. VARCHAR인 이유: 선택 이벤트 추가 시 무DDL 확장
    product_id      BIGINT      NULL,                      -- 의도적으로 FK 없음(로그 적재 경량화)
    properties      JSON        NULL,                      -- 타입별 부가정보. session_start의 ipHash는 서버 주입
    created_at      DATETIME(6) NOT NULL,                  -- 서버 수신 시각 (증분 분석 커서)
    PRIMARY KEY (id),
    UNIQUE KEY uk_behavior_client_event (client_event_id),
    KEY idx_behavior_member (member_id, created_at),
    KEY idx_behavior_guest  (guest_id, created_at),
    KEY idx_behavior_type   (event_type, created_at),
    KEY idx_behavior_prod   (product_id, created_at),
    KEY idx_behavior_sess   (session_key),
    KEY idx_behavior_time   (created_at),
    CONSTRAINT fk_behavior_guest FOREIGN KEY (guest_id) REFERENCES guest (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 적재는 전부 FE의 POST /api/events (배치, 인증 선택) — 서버 내부 publishEvent 적재는 폐기 (D31)
-- client_event_id 중복은 INSERT 전 검증 후 무시 — INSERT IGNORE로 퉁치면 중복 외 오류까지 삼키므로 주의 (D35)

-- ------------------------------------------------------------
-- BE 직접 로그 3종 (분석 에이전트 입력 — D32)
-- 전부 FK 미설정(append-only 로그 경량화). 기록 지점 상세 규칙은 01 문서 소관.
-- ------------------------------------------------------------

CREATE TABLE order_status_logs (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    order_id     BIGINT       NOT NULL,                    -- FK 미설정 (append-only 로그)
    from_status  VARCHAR(20)  NULL,                        -- 최초 생성 시 NULL
    to_status    VARCHAR(20)  NOT NULL,                    -- 주문: PENDING/PAID/PAYMENT_FAILED/CANCELLED · 아이템 이행: SHIPPING/DELIVERED/CANCELLED/RETURNED
    actor_type   ENUM('USER','SELLER','ADMIN','SYSTEM') NOT NULL,  -- 배송 전이=SYSTEM, 취소·반품 완료=USER(신청 주체), 결제=SYSTEM
    reason       VARCHAR(200) NULL,                        -- 결제 실패 코드, 취소·반품 사유(claim.reason)
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_oslog_order  (order_id, created_at),
    KEY idx_oslog_status (to_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- ORDERED(PAID와 같은 트랜잭션)·*_REQUESTED(claim이 정본)·CONFIRMED는 미기록. 교환 어휘 없음 (D34)
-- 같은 주문 여러 아이템의 동시 동일 전이(스케줄러 배치)는 주문 단위 1행만 (D32)

CREATE TABLE product_change_logs (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    product_id   BIGINT      NOT NULL,                     -- FK 미설정 (append-only 로그)
    change_type  ENUM('PRICE','STOCK','STATUS') NOT NULL,
    old_value    VARCHAR(50) NULL,
    new_value    VARCHAR(50) NULL,                         -- 품절 신호 = STOCK new_value 0 (SOLD_OUT 상태 미도입)
    created_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_pclog_prod (product_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 전후 값 동일 시 미기록. 주문에 의한 재고 -1도 미기록(order_item으로 복원) — 수동 조정·품절/재입고 전환만 (D32)

CREATE TABLE account_event_logs (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    member_id   BIGINT      NULL,                          -- 없는 계정 로그인 시도는 NULL + IP (무차별 대입 탐지 재료)
    event_type  ENUM('SIGNUP','LOGIN_SUCCESS','LOGIN_FAIL','LOGOUT','WITHDRAW') NOT NULL,
    ip_address  VARCHAR(45) NULL,                          -- IPv6 수용
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_aclog_member (member_id, created_at),
    KEY idx_aclog_ip     (ip_address, created_at),
    KEY idx_aclog_type   (event_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Spring Security AuthenticationSuccess/FailureHandler에서 적재. "마지막 로그인" 단일 출처 = LOGIN_SUCCESS (D32)

-- ============================================================
-- ERD에 없는 것들은 누락이 아니라 결정 (02 §7):
--   평점 평균/리뷰 수 컬럼(D9) · 개인화 프로필(D13 — LLM팀 소유)
--   채팅 세션(D12 존속분 — Redis TTL 휘발) · 상품 이미지 테이블(D14 — image_url 단일)
--   재고는 D33으로 도입됨(D8 폐기) — product.stock_quantity
-- ============================================================
