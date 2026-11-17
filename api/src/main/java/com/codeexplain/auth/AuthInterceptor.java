package com.codeexplain.auth;

import java.util.UUID;

import com.codeexplain.common.ApiException;
import com.codeexplain.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录拦截：把 Cookie 里的 JWT 验成 {@code userId} 塞进请求属性。
 *
 * <p>没有用 Spring Security 的过滤器链——本项目只需要「有没有登录」这一个判断，
 * 引入整套 Security 会带来一堆默认行为（默认登录页、CSRF token、403 页面），
 * 反而要花更多代码去关掉它们。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 控制器用 {@code @RequestAttribute(AuthInterceptor.USER_ID)} 取当前用户。 */
    public static final String USER_ID = "authUserId";

    private final SessionCookies cookies;
    private final JwtService jwt;

    public AuthInterceptor(SessionCookies cookies, JwtService jwt) {
        this.cookies = cookies;
        this.jwt = jwt;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = cookies.read(request);
        UUID userId = jwt.verify(token)
                .map(JwtService.Claims::userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        request.setAttribute(USER_ID, userId);
        return true;
    }
}
