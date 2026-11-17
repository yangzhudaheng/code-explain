package com.codeexplain.common;

import jakarta.servlet.http.HttpServletRequest;

/** 取客户端 IP。 */
public final class ClientIp {

    private ClientIp() {
    }

    /**
     * Vercel / Nginx 反代后 {@code getRemoteAddr()} 拿到的是代理地址，
     * 真实 IP 在 {@code X-Forwarded-For} 的第一段。
     *
     * <p>注意：这个头可以被客户端伪造，所以它只用于<b>频控分桶</b>，
     * 绝不能用于鉴权判断。伪造它最多让攻击者换个桶，而邮箱维度的 60 秒冷却仍然生效。
     */
    public static String of(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }
}
