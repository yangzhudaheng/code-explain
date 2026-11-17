package com.codeexplain.common;

/** 业务异常：携带错误码，由 {@link GlobalExceptionHandler} 统一转成 {@link ApiError}。 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code) {
        super(code.defaultMessage());
        this.code = code;
    }

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public static ApiException of(ErrorCode code) {
        return new ApiException(code);
    }
}
