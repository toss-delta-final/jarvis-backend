package com.jarvis.global.response;

import lombok.Getter;

/**
 * 도메인별 커스텀 예외의 공통 부모 — GlobalExceptionHandler가 ErrorCode로 매핑한다 (03 §6).
 * 컨트롤러 try-catch 금지, 서비스 레이어에서 던진다.
 * detail은 envelope error.detail로 나가는 부가 데이터(예: CART_OPTION_REQUIRED의 options).
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Object detail;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), null);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(ErrorCode errorCode, Object detail) {
        this(errorCode, errorCode.getMessage(), detail);
    }

    private BusinessException(ErrorCode errorCode, String message, Object detail) {
        super(message);
        this.errorCode = errorCode;
        this.detail = detail;
    }
}
