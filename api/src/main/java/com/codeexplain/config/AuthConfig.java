package com.codeexplain.config;

import com.codeexplain.auth.AuthInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 认证相关的 Bean 与拦截器注册。 */
@Configuration
public class AuthConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public AuthConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    /**
     * 强度 10：约 60ms 一次，登录/注册都能接受。
     * 调到 12 会让登录慢到 250ms，在 Vercel 这种按需计费的容器里不划算。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // 白名单：这几个接口本来就是「还没登录」时调的
                .excludePathPatterns(
                        "/api/auth/captcha",
                        "/api/auth/email-code",
                        "/api/auth/register",
                        "/api/auth/login",
                        "/api/auth/logout",
                        // 登录页要展示倒计时/密码规则，这个接口必须在未登录时可读
                        "/api/auth/policy",
                        "/api/ping",
                        "/api/db/**",
                        "/api/dev/**");
    }
}
