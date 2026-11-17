package com.codeexplain.common;

/**
 * 统一错误响应体。
 *
 * @param code    机器可读错误码，前端按它分支
 * @param message 面向用户的中文提示，可直接展示
 * @param field   出错的字段名（表单校验用），没有则为 null
 */
public record ApiError(String code, String message, String field) {

    public static ApiError of(ErrorCode code) {
        return new ApiError(code.name(), code.defaultMessage(), null);
    }

    public static ApiError of(ErrorCode code, String message) {
        return new ApiError(code.name(), message, null);
    }

    public static ApiError of(ErrorCode code, String message, String field) {
        return new ApiError(code.name(), message, field);
    }
}
