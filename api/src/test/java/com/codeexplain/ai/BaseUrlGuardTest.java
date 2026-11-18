package com.codeexplain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;

import com.codeexplain.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SSRF 防护的行为测试。
 *
 * <p>用注入的假解析器而不是真实 DNS：测试不该因为网络抖动变红，
 * 更需要能构造「域名看着正常、解析到 10.0.0.5」这种真实 DNS 才出现的场景。
 */
class BaseUrlGuardTest {

    private static BaseUrlGuard guardResolving(String address) {
        return new BaseUrlGuard(host -> new InetAddress[]{InetAddress.getByName(address)});
    }

    private static final BaseUrlGuard PUBLIC = guardResolving("93.184.216.34");

    @Test
    @DisplayName("公网 https 地址通过，并去掉尾部斜杠")
    void acceptsPublicHttps() {
        assertThat(PUBLIC.normalize("https://api.deepseek.com/")).isEqualTo("https://api.deepseek.com");
        assertThat(PUBLIC.normalize("  https://api.deepseek.com/v1  ")).isEqualTo("https://api.deepseek.com/v1");
    }

    @Test
    @DisplayName("非 https 一律拒绝")
    void rejectsHttp() {
        assertThatThrownBy(() -> PUBLIC.normalize("http://api.deepseek.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("https");
    }

    @Test
    @DisplayName("localhost / 内网域名 / 带账号密码的地址都被拒绝")
    void rejectsInternalNamesAndUserInfo() {
        assertThatThrownBy(() -> PUBLIC.normalize("https://localhost:8080"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PUBLIC.normalize("https://metadata.google.internal"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PUBLIC.normalize("https://db.internal"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PUBLIC.normalize("https://user:pass@api.deepseek.com"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PUBLIC.normalize("https://api.local"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("解析到私有 / 回环 / 链路本地地址的域名被拒绝")
    void rejectsPrivateResolutions() {
        for (String ip : new String[]{
                "127.0.0.1", "10.1.2.3", "172.16.0.9", "192.168.1.1",
                // 云环境 metadata 服务
                "169.254.169.254",
                // 运营商级 NAT 与保留段
                "100.64.0.1", "0.0.0.0", "198.18.0.1", "240.0.0.1"}) {
            assertThatThrownBy(() -> guardResolving(ip).normalize("https://evil.example.com"))
                    .as("应拒绝解析到 %s 的地址", ip)
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    @DisplayName("IPv6 回环与链路本地被拒绝")
    void rejectsIpv6Local() {
        assertThatThrownBy(() -> guardResolving("::1").normalize("https://evil.example.com"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> guardResolving("fe80::1").normalize("https://evil.example.com"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("域名解析失败时直接拒绝，而不是等到请求超时")
    void rejectsUnresolvableHost() {
        var guard = new BaseUrlGuard(host -> {
            throw new UnknownHostException(host);
        });

        assertThatThrownBy(() -> guard.normalize("https://no-such-host.example"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("解析失败");
    }

    @Test
    @DisplayName("空值与非 URL 文本被拒绝")
    void rejectsGarbage() {
        assertThatThrownBy(() -> PUBLIC.normalize(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PUBLIC.normalize("   ")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PUBLIC.normalize("api.deepseek.com")).isInstanceOf(ApiException.class);
    }
}
