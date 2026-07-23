package com.jarvis.product;

import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * I-17 커서 — 마지막 처리 상품의 (updatedAt, id)를 Base64URL 불투명 문자열로 인코딩(2026-07-22 LLM 합의).
 * AI는 해석하지 않고 다음 요청 since로 그대로 전달하고, Spring이 디코딩해 keyset 조회 조건으로 쓴다.
 */
public record ProductChangeCursor(LocalDateTime updatedAt, long id) {

    private static final String DELIMITER = "|";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public static String encode(LocalDateTime updatedAt, long id) {
        String raw = updatedAt + DELIMITER + id;
        return ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** since="0"·빈 값은 커서 없음(처음부터) → null. 그 외 해석 실패는 400 INVALID_CURSOR. */
    public static ProductChangeCursor decode(String since) {
        if (since == null || since.isBlank() || "0".equals(since)) {
            return null;
        }
        try {
            String raw = new String(DECODER.decode(since), StandardCharsets.UTF_8);
            int sep = raw.lastIndexOf(DELIMITER);
            if (sep < 0) {
                throw new BusinessException(ErrorCode.INVALID_CURSOR);
            }
            LocalDateTime updatedAt = LocalDateTime.parse(raw.substring(0, sep));
            long id = Long.parseLong(raw.substring(sep + 1));
            return new ProductChangeCursor(updatedAt, id);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new BusinessException(ErrorCode.INVALID_CURSOR);
        }
    }
}
