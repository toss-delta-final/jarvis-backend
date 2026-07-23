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
    // 재고는 상품 단위(02 D33). 합산 후 수량 > stock_quantity면 담기/수량변경 차단 — detail.availableStock 동반
    CART_STOCK_INSUFFICIENT(HttpStatus.BAD_REQUEST, "재고가 부족합니다."),

    // order / claim (04 §4 — Phase 3)
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    ORDER_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "주문 상품을 찾을 수 없습니다."),
    ADDRESS_NOT_FOUND(HttpStatus.NOT_FOUND, "배송지를 찾을 수 없습니다."),
    ORDER_PRODUCT_UNAVAILABLE(HttpStatus.BAD_REQUEST, "구매할 수 없는 상품이 포함되어 있습니다."),
    ORDER_INVALID_TRANSITION(HttpStatus.BAD_REQUEST, "현재 상태에서 처리할 수 없는 요청입니다."),
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

    // chat (04 §6 — Phase 5)
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅 세션이 만료되었거나 존재하지 않습니다."),
    SESSION_FORBIDDEN(HttpStatus.FORBIDDEN, "본인 세션이 아닙니다."),

    // internal (03 D4 — Phase 5)
    INTERNAL_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "서비스 토큰이 유효하지 않습니다."),
    NOT_IMPLEMENTED(HttpStatus.NOT_IMPLEMENTED, "아직 제공하지 않는 기능입니다."),
    // I-17 상품 동기화 커서 — 해석 불가/변조 시 AI는 since="0" 전체 재구축으로 폴백 (노션 I-17)
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "커서가 유효하지 않습니다."),
    CART_QUERY_INVALID(HttpStatus.BAD_REQUEST, "userId 또는 guestId 중 하나만 지정해야 합니다."),
    ORDER_INVALID_PARAM(HttpStatus.BAD_REQUEST, "요청 파라미터가 올바르지 않습니다."),

    // seller (04 §7·§10 — Phase 6)
    SELLER_BRAND_NOT_FOUND(HttpStatus.NOT_FOUND, "판매자에 연결된 브랜드가 없습니다."),
    PRODUCT_CATEGORY_INVALID(HttpStatus.BAD_REQUEST, "상품은 소분류 카테고리에만 등록할 수 있습니다."),
    // 판매자 화면 조회 파라미터 오류 — 계약(노션 S-1/S-3)이 엔드포인트별 code를 요구 (S-2는 ORDER_INVALID_PARAM 재사용)
    SELLER_INVALID_PARAM(HttpStatus.BAD_REQUEST, "요청 파라미터가 올바르지 않습니다."),
    PRODUCT_INVALID_PARAM(HttpStatus.BAD_REQUEST, "요청 파라미터가 올바르지 않습니다."),

    // seller 분석·상품 쓰기 (노션 명세 정합화 — 2026-07-18)
    INVALID_PERIOD(HttpStatus.BAD_REQUEST, "조회 기간이 올바르지 않습니다."),
    INVALID_GROUP_BY(HttpStatus.BAD_REQUEST, "groupBy 또는 eventType 값이 올바르지 않습니다."),
    MISSING_FIELD(HttpStatus.UNPROCESSABLE_ENTITY, "필수 입력값이 누락되었습니다."),
    INVALID_PRICE(HttpStatus.UNPROCESSABLE_ENTITY, "판매가는 정가를 넘을 수 없습니다."),
    INVALID_STOCK(HttpStatus.UNPROCESSABLE_ENTITY, "재고 수량이 올바르지 않습니다."),
    ALREADY_HIDDEN(HttpStatus.CONFLICT, "이미 숨김 처리된 상품입니다.");

    private final HttpStatus status;
    private final String message;
}
