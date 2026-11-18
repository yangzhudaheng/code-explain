package com.codeexplain.ai;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

import com.codeexplain.common.ApiException;
import com.codeexplain.common.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 模型 base-url 的 SSRF 防护。
 *
 * <p>允许用户自填 base-url 就等于在服务端放了一台「内网探测器」：填
 * {@code https://127.0.0.1:8080/api/admin} 就能让我们带着服务器自己的网络位置去请求它。
 * 云环境里这个位置往往能摸到 metadata 服务（169.254.169.254）和只监听内网的组件。
 *
 * <p>三道闸：
 * <ol>
 *   <li>必须 https（挡掉明文与一部分中间人）；</li>
 *   <li>主机名不能是 localhost / *.internal / *.local 这类内网命名；</li>
 *   <li>解析出的每个地址都不能落在私有、回环、链路本地、保留网段。</li>
 * </ol>
 *
 * <p>局限要说清：这里只在保存配置时校验一次，DNS 重绑定（第一次解析公网、真正请求时
 * 解析到内网）理论上仍可绕过。要彻底解决得在 HTTP 客户端层做「连接时校验对端 IP」，
 * 那需要自定义 SocketFactory，代价与本项目阶段不相称。现在的校验挡住了 99% 的误填与随手探测。
 */
@Component
public class BaseUrlGuard {

    /** 域名解析入口；单独抽出来是为了测试能注入「已知地址」，不依赖真实 DNS。 */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final HostResolver resolver;

    public BaseUrlGuard() {
        this(InetAddress::getAllByName);
    }

    BaseUrlGuard(HostResolver resolver) {
        this.resolver = resolver;
    }

    /** 不要在这些网段上发起请求。 */
    private static final Set<String> BLOCKED_HOST_SUFFIXES =
            Set.of(".internal", ".local", ".localhost", ".lan", ".home.arpa");

    private static final Set<String> BLOCKED_HOSTS = Set.of("localhost", "metadata.google.internal");

    /** 规范化成「不带尾斜杠」的 base url；不合法直接抛 400。 */
    public String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCode.BASE_URL_FORBIDDEN, "请填写模型地址");
        }
        String trimmed = raw.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.BASE_URL_FORBIDDEN, "模型地址不是合法的 URL");
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ApiException(ErrorCode.BASE_URL_FORBIDDEN, "模型地址必须以 https:// 开头");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ApiException(ErrorCode.BASE_URL_FORBIDDEN, "模型地址缺少主机名");
        }
        // userinfo（https://user:pass@host）常被用来把真实主机藏在 @ 后面
        if (uri.getUserInfo() != null) {
            throw new ApiException(ErrorCode.BASE_URL_FORBIDDEN, "模型地址不能包含用户名密码");
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (BLOCKED_HOSTS.contains(host)) {
            throw new ApiException(ErrorCode.BASE_URL_FORBIDDEN, "模型地址不能指向本机或内网");
        }
        for (String suffix : BLOCKED_HOST_SUFFIXES) {
            if (host.endsWith(suffix)) {
                throw new ApiException(ErrorCode.BASE_URL_FORBIDDEN, "模型地址不能指向内网域名");
            }
        }
        for (InetAddress address : resolve(host)) {
            if (isBlocked(address)) {
                throw new ApiException(ErrorCode.BASE_URL_FORBIDDEN, "模型地址解析到了内网地址，已拒绝");
            }
        }
        return trimmed;
    }

    private InetAddress[] resolve(String host) {
        try {
            return resolver.resolve(host);
        } catch (UnknownHostException e) {
            // 解析不了就说明请求也发不出去，早点告诉用户「地址写错了」比等到超时好
            throw new ApiException(ErrorCode.BASE_URL_FORBIDDEN, "模型地址解析失败，请检查域名是否正确");
        }
    }

    private boolean isBlocked(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                // 100.64/10 运营商级 NAT、192.0.0/24 保留段等在 Java 里没有现成判断
                || inCgnat(address)
                || inReserved(address);
    }

    private boolean inCgnat(InetAddress address) {
        byte[] b = address.getAddress();
        return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
    }

    private boolean inReserved(InetAddress address) {
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            // 0.0.0.0/8、192.0.0.0/24、198.18.0.0/15（基准测试保留）、240.0.0.0/4
            return first == 0
                    || (first == 192 && second == 0)
                    || (first == 198 && (second == 18 || second == 19))
                    || first >= 240;
        }
        return false;
    }
}
