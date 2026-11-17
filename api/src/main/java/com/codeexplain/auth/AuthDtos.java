package com.codeexplain.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 认证接口的请求/响应结构。全部用 record，字段即契约。 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record CaptchaResponse(String id, String image) {
    }

    public record EmailCodeRequest(
            @NotBlank(message = "请填写邮箱")
            @Email(message = "邮箱格式不正确")
            @Size(max = 254, message = "邮箱过长")
            String email,

            @NotBlank(message = "验证码已失效，请刷新")
            String captchaId,

            @NotBlank(message = "请填写图形验证码")
            String captchaCode) {
    }

    /**
     * @param sent            是否已发出（dev 模式下没真发，但前端流程一致）
     * @param devMode         true 表示没配 SMTP，验证码只在服务端日志里
     * @param cooldownSeconds 前端倒计时秒数，由服务端下发避免各处硬编码不一致
     */
    public record EmailCodeResponse(boolean sent, boolean devMode, int cooldownSeconds) {
    }

    public record RegisterRequest(
            @NotBlank(message = "请填写邮箱")
            @Email(message = "邮箱格式不正确")
            @Size(max = 254, message = "邮箱过长")
            String email,

            @NotBlank(message = "请填写邮箱验证码")
            String code,

            @NotBlank(message = "请填写密码")
            String password,

            @Size(max = 40, message = "昵称最长 40 字")
            String displayName) {
    }

    public record LoginRequest(
            @NotBlank(message = "请填写邮箱")
            String email,

            @NotBlank(message = "请填写密码")
            String password) {
    }

    /** 对外的用户视图：没有 password_hash，也没有内部状态字段。 */
    public record UserResponse(UUID id, String email, String displayName, boolean emailVerified, Instant createdAt) {

        public static UserResponse from(UserRepository.AuthUser user) {
            return new UserResponse(user.id(), user.email(), user.displayName(),
                    user.emailVerified(), user.createdAt());
        }
    }
}
