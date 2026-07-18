package com.jarvis.global.response;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        return ResponseEntity.status(code.getStatus())
                .body(ApiResponse.error(code, e.getMessage(), e.getDetail()));
    }

    // @Valid 검증 실패 → VALIDATION_ERROR + fields[{field, message}] (03 D2, 2026-07-17 FE 요청)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        List<ApiResponse.FieldErrorDetail> fields = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiResponse.FieldErrorDetail(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.getStatus())
                .body(ApiResponse.validationError(fields));
    }

    // @Validated 쿼리/경로 파라미터 검증 실패 (P-3 sort 등) → 400 VALIDATION_ERROR
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            jakarta.validation.ConstraintViolationException e) {
        List<ApiResponse.FieldErrorDetail> fields = e.getConstraintViolations().stream()
                .map(v -> new ApiResponse.FieldErrorDetail(
                        String.valueOf(v.getPropertyPath()), v.getMessage()))
                .toList();
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.getStatus())
                .body(ApiResponse.validationError(fields));
    }

    // 파라미터 타입 불일치(숫자 자리에 문자 등) → 400 VALIDATION_ERROR
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.getStatus())
                .body(ApiResponse.error(ErrorCode.VALIDATION_ERROR));
    }

    // body 파싱 실패(JSON 문법 오류, enum 불일치 등)도 400 VALIDATION_ERROR로 통일
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.getStatus())
                .body(ApiResponse.error(ErrorCode.VALIDATION_ERROR));
    }

    // 존재하지 않는 경로도 envelope 형식 404 (06 Phase 0 완료 조건)
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(ErrorCode.RESOURCE_NOT_FOUND.getStatus())
                .body(ApiResponse.error(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // DB 제약(UNIQUE 등) 위반 → 409. check-then-act가 경합에 진 순간(가입 이메일·찜·리뷰 신고 등)을 500 대신 409로 (02 D26)
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(
            org.springframework.dao.DataIntegrityViolationException e) {
        log.warn("Data integrity violation (동시성 경합 또는 중복)", e);
        return ResponseEntity.status(ErrorCode.RESOURCE_CONFLICT.getStatus())
                .body(ApiResponse.error(ErrorCode.RESOURCE_CONFLICT));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR));
    }
}
