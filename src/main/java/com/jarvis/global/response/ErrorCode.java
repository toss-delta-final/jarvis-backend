package com.jarvis.global.response;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 에러 코드 목록의 원본은 04 §11 — 구현 중 추가 시 문서에도 반영한다.
 * 형식: <도메인>_<사유> 대문자 스네이크, message는 사용자 노출 가능한 한국어 문장 (03 D2).
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    RESOURCE_CONFLICT(HttpStatus.CONFLICT, "이미 처리되었거나 중복된 요청입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

    // auth (04 §11 · 03 D2 — 401 2종 분리: 토큰 없음 vs 만료)
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "인증이 만료되었습니다."),
    AUTH_LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    // 로그인·가입 시도 제한 (07 §3-4) — 계정 잠금이 아니라 일시 차단이라 창이 지나면 자동 해제된다.
    // 응답에 Retry-After 헤더가 함께 나간다.
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "시도가 너무 많습니다. 잠시 후 다시 시도해주세요."),
    AUTH_FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

    // member
    MEMBER_EMAIL_DUPLICATE(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."),

    // catalog
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다."),
    BRAND_NOT_FOUND(HttpStatus.NOT_FOUND, "브랜드를 찾을 수 없습니다."),

    // cart (04 §3 — Phase 3)
    CART_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "장바구니 항목을 찾을 수 없습니다."),
    CART_OPTION_REQUIRED(HttpStatus.BAD_REQUEST, "옵션을 선택해 주세요."),
    CART_OPTION_INVALID(HttpStatus.BAD_REQUEST, "해당 상품의 옵션이 아닙니다."),
    // 재고는 옵션 단위(02 D33 개정) — 담은 옵션의 재고를 넘으면 담기/수량변경 차단, detail.availableStock 동반.
    // 전 옵션이 품절인 상품에 optionId 없이 담기를 시도해도 이 코드다(CART_OPTION_REQUIRED는 빈 목록이 돼
    // LLM이 되물을 이름을 잃는다 — 2026-08-09 AI팀 실측)
    CART_STOCK_INSUFFICIENT(HttpStatus.BAD_REQUEST, "재고가 부족합니다."),

    // order / claim (04 §4 — Phase 3)
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    ORDER_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "주문 상품을 찾을 수 없습니다."),
    ADDRESS_NOT_FOUND(HttpStatus.NOT_FOUND, "배송지를 찾을 수 없습니다."),
    // 미판매/HIDDEN 또는 재고 부족 — detail.unavailableItems[{productId,name,reason}] 동반 (04 §4, 2026-08-05 재고 포함으로 확장)
    ORDER_PRODUCT_UNAVAILABLE(HttpStatus.BAD_REQUEST, "구매할 수 없는 상품이 포함되어 있습니다."),
    ORDER_INVALID_TRANSITION(HttpStatus.BAD_REQUEST, "현재 상태에서 처리할 수 없는 요청입니다."),
    // I-30 재발송 — 멱등 200을 내지 않는다. HITL 쓰기는 "이미 된 일"과 "방금 한 일"을 구분해야
    // 에이전트의 거짓 성공 보고를 막는다(I-12와 같은 논리, 04 §11)
    ORDER_ALREADY_SHIPPED(HttpStatus.CONFLICT, "이미 발송 처리된 주문 상품입니다."),
    CLAIM_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "현재 상태에서 취소/반품을 신청할 수 없습니다."),
    CLAIM_ALREADY_REQUESTED(HttpStatus.CONFLICT, "이미 처리 중인 신청이 있습니다."),

    // mypage (04 §5 — Phase 4)
    REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, "후기를 찾을 수 없습니다."),
    REVIEW_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "후기를 작성할 수 없는 주문 상품입니다."),
    REVIEW_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 후기를 작성한 주문 상품입니다."),
    REVIEW_SELF_REPORT(HttpStatus.BAD_REQUEST, "본인 후기는 신고할 수 없습니다."),
    REVIEW_REPORT_DUPLICATE(HttpStatus.CONFLICT, "이미 신고한 후기입니다."),
    WISHLIST_DUPLICATE(HttpStatus.CONFLICT, "이미 찜한 상품입니다."),
    WISHLIST_NOT_FOUND(HttpStatus.NOT_FOUND, "찜한 상품이 아닙니다."),
    ADDRESS_LAST_UNDELETABLE(HttpStatus.BAD_REQUEST, "유일한 배송지는 삭제할 수 없습니다."),

    // AI 취향 프로필 (노션 M-11~M-16 · I-32~I-37) — 레인 공통 코드는 변환하지만 PROFILE_*는
    // AI가 준 값을 그대로 FE에 노출한다. 두 레인이 자원 코드를 공유하는 C-2↔I-2와 같은 구도다.
    PROFILE_EDGE_NOT_FOUND(HttpStatus.NOT_FOUND, "취향 항목을 찾을 수 없습니다."),
    // detail.graphVersion에 최신 값을 실어 FE가 M-11로 다시 읽고 재시도하게 한다
    PROFILE_VERSION_CONFLICT(HttpStatus.CONFLICT, "다른 곳에서 변경되었습니다."),
    // 위 409와 FE 대응이 반대라 코드를 나눈다 — 이쪽은 재시도해도 소용없다 (구매 이력 파생)
    PROFILE_EDGE_NOT_EDITABLE(HttpStatus.CONFLICT, "구매 기록에서 만들어진 항목은 수정할 수 없습니다."),

    // chat (04 §6 — Phase 5)
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅 세션이 만료되었거나 존재하지 않습니다."),
    SESSION_FORBIDDEN(HttpStatus.FORBIDDEN, "본인 세션이 아닙니다."),
    // CH-7 승계 — AI가 거부한 사유를 그대로 옮긴다(노션 I-23 · jarvis-ai #187)
    SESSION_ACTIVE(HttpStatus.CONFLICT, "대화가 진행 중입니다. 잠시 후 다시 시도해 주세요."),
    SESSION_CLAIM_CONFLICT(HttpStatus.CONFLICT, "이미 정리되었거나 다른 계정으로 승계된 세션입니다."),
    SESSION_CLAIM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 대화를 이어받을 수 없습니다. 잠시 후 다시 시도해 주세요."),

    // internal (03 D4 — Phase 5)
    INTERNAL_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "서비스 토큰이 유효하지 않습니다."),
    NOT_IMPLEMENTED(HttpStatus.NOT_IMPLEMENTED, "아직 제공하지 않는 기능입니다."),
    // I-17 상품 동기화 커서 — 해석 불가/변조 시 AI는 since="0" 전체 재구축으로 폴백 (노션 I-17)
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "커서가 유효하지 않습니다."),
    // I-18 전용 — I-24·I-25는 같은 XOR 위반이지만 VALIDATION_ERROR다(2026-08-05 확정:
    // 합의되지 않은 새 code를 계약에 만들지 않는다). 찜(I-27·I-28)도 같은 이유로 이 계열을 쓰지 않는다
    CART_QUERY_INVALID(HttpStatus.BAD_REQUEST, "userId 또는 guestId 중 하나만 지정해야 합니다."),
    ORDER_INVALID_PARAM(HttpStatus.BAD_REQUEST, "요청 파라미터가 올바르지 않습니다."),

    // seller (04 §7·§10 — Phase 6)
    SELLER_BRAND_NOT_FOUND(HttpStatus.NOT_FOUND, "판매자에 연결된 브랜드가 없습니다."),
    PRODUCT_CATEGORY_INVALID(HttpStatus.BAD_REQUEST, "상품은 소분류 카테고리에만 등록할 수 있습니다."),
    // 판매자 화면 조회 파라미터 오류 — 계약(노션 S-1/S-3)이 엔드포인트별 code를 요구 (S-2는 ORDER_INVALID_PARAM 재사용)
    SELLER_INVALID_PARAM(HttpStatus.BAD_REQUEST, "요청 파라미터가 올바르지 않습니다."),
    PRODUCT_INVALID_PARAM(HttpStatus.BAD_REQUEST, "요청 파라미터가 올바르지 않습니다."),
    // S-6 업로드 URL 발급 — 형식 오류와 크기 초과를 나눈 이유는 FE 대응이 반대라서다:
    // 형식은 같은 사진으로 재시도해도 소용없고, 크기는 "더 작은 사진으로" 안내가 필요하다
    // (PROFILE_VERSION_CONFLICT ↔ PROFILE_EDGE_NOT_EDITABLE과 같은 기준. 2026-08-09 FE 요청·합의)
    IMAGE_TYPE_UNSUPPORTED(HttpStatus.BAD_REQUEST, "지원하지 않는 이미지 형식입니다."),
    IMAGE_TOO_LARGE(HttpStatus.BAD_REQUEST, "이미지 용량이 너무 큽니다."),

    // seller 분석·상품 쓰기 (노션 명세 정합화 — 2026-07-18)
    INVALID_PERIOD(HttpStatus.BAD_REQUEST, "조회 기간이 올바르지 않습니다."),
    INVALID_GROUP_BY(HttpStatus.BAD_REQUEST, "groupBy 또는 eventType 값이 올바르지 않습니다."),
    MISSING_FIELD(HttpStatus.UNPROCESSABLE_ENTITY, "필수 입력값이 누락되었습니다."),
    INVALID_PRICE(HttpStatus.UNPROCESSABLE_ENTITY, "판매가는 정가를 넘을 수 없습니다."),
    INVALID_STOCK(HttpStatus.UNPROCESSABLE_ENTITY, "재고 수량이 올바르지 않습니다."),
    // I-12 재삭제. 숨김(HIDDEN)에서 삭제로 가는 건 정상 전이라 막지 않는다 — 이미 DELETED일 때만 충돌
    ALREADY_DELETED(HttpStatus.CONFLICT, "이미 삭제된 상품입니다."),
    // I-10·I-11 — 삭제된 상품은 판매자에게 보이지 않으므로 수정·복구 대상이 아니다. 404가 아닌 이유는
    // 상품이 실제로 존재하고 주문 내역에도 남아 있어서다(에이전트가 "없는 상품"이라 답하면 사실과 다름)
    PRODUCT_DELETED(HttpStatus.CONFLICT, "삭제된 상품은 변경할 수 없습니다.");

    private final HttpStatus status;
    private final String message;
}
