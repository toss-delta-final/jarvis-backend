package com.jarvis.seller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * S-6 업로드 URL 발급 요청 (04 §7, 노션 S-6).
 *
 * <p>{@code size}를 받는 이유 — presigned PUT은 크기 <b>상한</b>을 걸 수 없다. 서명에 들어가는
 * {@code Content-Length}는 최대치가 아니라 <b>일치</b> 조건이라, 클라이언트가 선언한 값을 검증해
 * 서명에 박고 브라우저가 정확히 그 크기로 올리게 하는 방식만 강제가 된다. 범위 제한이 필요하면
 * presigned POST({@code content-length-range})로 가야 하는데 업로드 코드가 통째로 바뀐다.
 *
 * <p>누락·형식 오류는 bean validation이 400 VALIDATION_ERROR로 낸다. 확장자·타입·크기 초과는
 * 서비스가 계약별 code로 구분한다.
 */
public record SellerImageUploadUrlRequest(
        @NotBlank String filename,
        @NotBlank String contentType,
        @NotNull @Positive Long size) {
}
