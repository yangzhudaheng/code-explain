package com.codeexplain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final String secret = "a".repeat(48);

    private JwtService service(Duration ttl) {
        return new JwtService(secret, ttl, json);
    }

    @Test
    @DisplayName("签发的令牌能验回同一个用户")
    void roundTrip() {
        UUID id = UUID.randomUUID();
        String token = service(Duration.ofHours(1)).issue(id, "a@b.com");

        var claims = service(Duration.ofHours(1)).verify(token);
        assertThat(claims).isPresent();
        assertThat(claims.get().userId()).isEqualTo(id);
        assertThat(claims.get().email()).isEqualTo("a@b.com");
    }

    @Test
    @DisplayName("篡改 payload 后签名校验必须失败")
    void tamperedPayloadRejected() throws Exception {
        UUID id = UUID.randomUUID();
        JwtService svc = service(Duration.ofHours(1));
        String token = svc.issue(id, "a@b.com");
        String[] parts = token.split("\\.");

        String fakePayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + UUID.randomUUID() + "\",\"email\":\"hacker@x.com\",\"iat\":0,\"exp\":9999999999}")
                        .getBytes());
        String forged = parts[0] + "." + fakePayload + "." + parts[2];

        assertThat(svc.verify(forged)).isEmpty();
    }

    @Test
    @DisplayName("alg 改成 none 必须被拒（经典降级攻击）")
    void algNoneRejected() throws Exception {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes());
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"00000000-0000-0000-0000-000000000000\",\"exp\":9999999999}".getBytes());

        assertThat(service(Duration.ofHours(1)).verify(header + "." + payload + ".")).isEmpty();
    }

    @Test
    @DisplayName("过期令牌被拒")
    void expiredRejected() {
        // 负 TTL 直接造一个已过期的令牌，避免测试里 sleep
        String token = service(Duration.ofHours(-2)).issue(UUID.randomUUID(), "a@b.com");
        assertThat(service(Duration.ofHours(1)).verify(token)).isEmpty();
    }

    @Test
    @DisplayName("乱码、分段数不对、空值一律拒绝且不抛异常")
    void garbageRejected() {
        JwtService svc = service(Duration.ofHours(1));
        assertThat(svc.verify(null)).isEmpty();
        assertThat(svc.verify("")).isEmpty();
        assertThat(svc.verify("abc")).isEmpty();
        assertThat(svc.verify("a.b")).isEmpty();
        assertThat(svc.verify("a.b.c")).isEmpty();
    }

    @Test
    @DisplayName("配了但太短的密钥必须启动失败，不能静默降级")
    void shortSecretRejected() {
        assertThatThrownBy(() -> new JwtService("short", Duration.ofHours(1), json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    @DisplayName("没配密钥时用临时密钥，仍能正常签发")
    void blankSecretFallsBackToEphemeral() {
        JwtService svc = new JwtService("", Duration.ofHours(1), json);
        String token = svc.issue(UUID.randomUUID(), "a@b.com");
        assertThat(svc.verify(token)).isPresent();
    }
}
