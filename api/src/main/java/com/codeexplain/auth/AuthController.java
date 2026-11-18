package com.codeexplain.auth;

import java.util.UUID;

import com.codeexplain.auth.AuthDtos.CaptchaResponse;
import com.codeexplain.auth.AuthDtos.EmailCodeRequest;
import com.codeexplain.auth.AuthDtos.EmailCodeResponse;
import com.codeexplain.auth.AuthDtos.LoginRequest;
import com.codeexplain.auth.AuthDtos.RegisterRequest;
import com.codeexplain.auth.AuthDtos.UserResponse;
import com.codeexplain.common.ApiException;
import com.codeexplain.common.ClientIp;
import com.codeexplain.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 认证接口。除 {@code /me} 外都是「还没登录」时调用的。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final CaptchaService captchas;
    private final EmailCodeService codes;
    private final AuthService auth;
    private final UserRepository users;
    private final JwtService jwt;
    private final SessionCookies cookies;

    public AuthController(CaptchaService captchas, EmailCodeService codes, AuthService auth,
                          UserRepository users, JwtService jwt, SessionCookies cookies) {
        this.captchas = captchas;
        this.codes = codes;
        this.auth = auth;
        this.users = users;
        this.jwt = jwt;
        this.cookies = cookies;
    }

    /** 刷新即换新图；旧 id 的答案留在库里自然过期，不影响新请求。 */
    @GetMapping("/captcha")
    public CaptchaResponse captcha(HttpServletRequest request) {
        var captcha = captchas.create(ClientIp.of(request));
        return new CaptchaResponse(captcha.id(), captcha.imageBase64());
    }

    @PostMapping("/email-code")
    public EmailCodeResponse sendEmailCode(@Valid @RequestBody EmailCodeRequest body,
                                           HttpServletRequest request) {
        // 图形码先消费掉：错了就不该往下发邮件，否则等于给刷邮件的人开了直通车
        if (!captchas.verifyAndConsume(body.captchaId(), body.captchaCode())) {
            throw new ApiException(ErrorCode.CAPTCHA_INVALID);
        }
        boolean devMode = codes.issue(AuthService.normalizeEmail(body.email()), ClientIp.of(request));
        return new EmailCodeResponse(true, devMode, (int) EmailCodeService.EMAIL_COOLDOWN.toSeconds());
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest body) {
        var user = auth.register(body.email(), body.code(), body.password(), body.displayName());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.create(jwt.issue(user.id(), user.email())).toString())
                .body(UserResponse.from(user));
    }

    @PostMapping("/login")
    public ResponseEntity<UserResponse> login(@Valid @RequestBody LoginRequest body) {
        var user = auth.login(body.email(), body.password());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.create(jwt.issue(user.id(), user.email())).toString())
                .body(UserResponse.from(user));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                .build();
    }

    /** 前端启动时用它判断「是否已登录」，顺带拿到展示用的用户信息。 */
    @GetMapping("/me")
    public UserResponse me(@RequestAttribute(AuthInterceptor.USER_ID) UUID userId) {
        return users.findById(userId)
                .map(UserResponse::from)
                // 令牌有效但用户已被删：当成未登录，别让前端卡在半登录状态
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }

    /** 暴露冷却时长，前端倒计时与服务端规则保持同一来源。 */
    @GetMapping("/policy")
    public PolicyResponse policy() {
        return new PolicyResponse(
                (int) EmailCodeService.EMAIL_COOLDOWN.toSeconds(),
                (int) EmailCodeService.CODE_TTL.toMinutes(),
                EmailCodeService.MAX_ATTEMPTS,
                EmailCodeService.MAX_PER_IP_DAILY,
                8);
    }

    public record PolicyResponse(int emailCooldownSeconds, int codeTtlMinutes,
                                 int maxCodeAttempts, int maxPerIpDaily, int minPasswordLength) {
    }
}
