package com.jarvis.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 모든 API 응답의 공통 envelope (03 D2).
 * 성공: {"success": true, "data": ...} / 실패: {"success": false, "error": {"code", "message", "fields"?, "detail"?}}
 * detail은 에러 부가 데이터(예: CART_OPTION_REQUIRED의 options 목록 — 05 §I-2, Phase 5 추가).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, ErrorBody error) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> error(ErrorCode code) {
        return new ApiResponse<>(false, null, new ErrorBody(code.name(), code.getMessage(), null, null));
    }

    public static ApiResponse<Void> error(ErrorCode code, String message) {
        return new ApiResponse<>(false, null, new ErrorBody(code.name(), message, null, null));
    }

    public static ApiResponse<Void> error(ErrorCode code, String message, Object detail) {
        return new ApiResponse<>(false, null, new ErrorBody(code.name(), message, null, detail));
    }

    public static ApiResponse<Void> validationError(List<FieldErrorDetail> fields) {
        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return new ApiResponse<>(false, null, new ErrorBody(code.name(), code.getMessage(), fields, null));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String code, String message, List<FieldErrorDetail> fields, Object detail) {
    }

    public record FieldErrorDetail(String field, String message) {
    }
}
