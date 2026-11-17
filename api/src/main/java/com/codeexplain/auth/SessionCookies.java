package com.codeexplain.auth;

import java.time.Duration;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 会话 Cookie 的读写。
 *
 * <p>为什么用 httpOnly Cookie 而不是 localStorage + Authorization 头：
 * localStorage 里的令牌任何 XSS 都能读走，而 httpOnly Cookie 脚本拿不到。
 * 代价是要配 SameSite 防 CSRF，这里用 {@code Lax}——
 * 它允许顶层导航带 Cookie（用户直接输网址进得来），又拦掉了跨站 POST。
 *
 * <p>{@code Secure} 由配置控制：本地 http://localhost 不能带 Secure，否则浏览器直接丢弃 Cookie。
 * 线上必须开，否则同域下的 http 请求会明文带出令牌。
 */
@Component
public class SessionCookies {

    public static final String NAME = "ce_session";

    private final JwtService jwt;
    private final boolean secure;
    private final String sameSite;

    public SessionCookies(JwtService jwt,
                          @Value("${app.auth.cookie-secure:false}") boolean secure,
                          @Value("${app.auth.cookie-same-site:Lax}") String sameSite) {
        this.jwt = jwt;
        this.secure = secure;
        this.sameSite = sameSite;
    }

    public ResponseCookie create(String token) {
        return base(token).maxAge(jwt.ttl()).build();
    }

    /** 登出：用同名同路径的过期 Cookie 覆盖，浏览器才会真正删掉它。 */
    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/");
    }

    public String read(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (var cookie : request.getCookies()) {
            if (NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
