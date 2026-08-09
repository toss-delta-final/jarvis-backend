package com.jarvis.seller.dto;

/**
 * S-6 응답 — 두 URL을 나눠 내려준다.
 *
 * @param uploadUrl S3 PUT 전용. 서명·만료 파라미터가 붙어 1,000자를 넘고 5분 뒤 죽는다. 저장 금지.
 * @param imageUrl  쿼리스트링 없는 canonical URL. {@code product.image_url}(VARCHAR(500))에 저장할 값.
 *                  <b>uploadUrl을 저장하면 만료 시점에 이미지가 죽고 오류가 안 나 아무도 모른다.</b>
 */
public record SellerImageUploadUrlResponse(String uploadUrl, String imageUrl) {
}
