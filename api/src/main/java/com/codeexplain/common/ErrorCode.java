package com.codeexplain.common;

import org.springframework.http.HttpStatus;

/**
 * 认证域的错误码。
 *
 * <p>每个错误码都自带 HTTP 状态和面向用户的中文文案，前端只需按 {@code code} 分支，
 * 不需要解析 message——文案将来要改（多语言、更友好的措辞）时不至于连前端一起改。
 */
public enum ErrorCode {

    // 400 —— 请求本身有问题，用户改一下就能过
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "请求参数不合法"),
    CAPTCHA_INVALID(HttpStatus.BAD_REQUEST, "图形验证码不正确"),
    CAPTCHA_EXPIRED(HttpStatus.BAD_REQUEST, "图形验证码已过期，请刷新后重试"),
    EMAIL_CODE_INVALID(HttpStatus.BAD_REQUEST, "邮箱验证码不正确"),
    EMAIL_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "邮箱验证码已过期，请重新获取"),
    EMAIL_CODE_TOO_MANY_ATTEMPTS(HttpStatus.BAD_REQUEST, "验证码尝试次数过多，请重新获取"),
    PASSWORD_TOO_WEAK(HttpStatus.BAD_REQUEST, "密码至少 8 位，且需同时包含字母和数字"),
    EMAIL_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "邮箱尚未验证"),

    // 401 —— 没登录或凭据错误
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "请先登录"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "邮箱或密码错误"),

    // 409 —— 状态冲突
    EMAIL_TAKEN(HttpStatus.CONFLICT, "该邮箱已注册"),

    // 429 —— 触发频控
    RATE_LIMITED_EMAIL(HttpStatus.TOO_MANY_REQUESTS, "该邮箱请求过于频繁，请 60 秒后再试"),
    RATE_LIMITED_IP(HttpStatus.TOO_MANY_REQUESTS, "今日验证码发送次数已达上限，请明天再试"),

    // 500 —— 服务端问题
    MAIL_SEND_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "验证码邮件发送失败，请稍后重试"),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "服务器开小差了，请稍后重试");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
