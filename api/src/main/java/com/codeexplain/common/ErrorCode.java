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

    // 404 —— 资源不存在，或不属于当前用户（不区分，避免探测别人的 id）
    NOT_FOUND(HttpStatus.NOT_FOUND, "接口不存在"),
    CANVAS_NOT_FOUND(HttpStatus.NOT_FOUND, "画布不存在或已被删除"),

    // 405 —— 路径存在但 HTTP 方法不对
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持"),

    // 409 —— 状态冲突
    EMAIL_TAKEN(HttpStatus.CONFLICT, "该邮箱已注册"),
    CANVAS_VERSION_CONFLICT(HttpStatus.CONFLICT, "画布已在别处被修改，请重新加载后再保存"),
    CANVAS_LIMIT_REACHED(HttpStatus.CONFLICT, "画布数量已达上限，请先删除不用的画布"),

    // 413 —— 请求体太大
    CANVAS_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "画布内容过大，保存失败"),

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
