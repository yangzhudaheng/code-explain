package com.codeexplain.common;

/** 业务异常：携带错误码，由 {@link GlobalExceptionHandler} 统一转成 {@link ApiError}。 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    /** 出错的字段名，用于前端把提示定位到具体输入项；没有则为 null。 */
    private final String field;

    public ApiException(ErrorCode code) {
        this(code, code.defaultMessage(), null);
    }

    public ApiException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ApiException(ErrorCode code, String message, String field) {
        super(message);
        this.code = code;
        this.field = field;
    }

    public ErrorCode code() {
        return code;
    }

    public String field() {
        return field;
    }

    public static ApiException of(ErrorCode code) {
        return new ApiException(code);
    }
}
