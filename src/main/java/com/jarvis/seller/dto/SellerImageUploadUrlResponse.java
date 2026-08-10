package com.jarvis.seller.dto;

/**
 * S-6 응답 — 두 URL을 나눠 내려준다.
 *
 * @param uploadUrl S3 PUT 전용. 서명·만료 파라미터가 붙어 1,000자를 넘고 5분 뒤 죽는다. 저장 금지.
 * @param imageUrl  쿼리스트링 없는 canonical URL. 상품 등록(I-10)·수정(I-11) 요청에 넘길 값이다.
 *                  <b>uploadUrl을 저장하면 만료 시점에 이미지가 죽고 오류가 안 나 아무도 모른다.</b>
 *                  이 값도 <b>24시간짜리 임시 주소</b>라 어디에도 보관하면 안 된다 (2026-08-10) —
 *                  {@code product.image_url}에 남는 값은 등록 때 서버가 만드는 최종 주소다.
 */
public record SellerImageUploadUrlResponse(String uploadUrl, String imageUrl) {
}
