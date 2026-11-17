package com.codeexplain.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * 数据库连接配置。
 *
 * <p>托管平台给的连接串格式不统一：Neon / Vercel 集成注入的是
 * {@code postgres://user:pass@host/db?sslmode=require}，而 JDBC 要的是
 * {@code jdbc:postgresql://host/db?sslmode=require}，账号密码要单独给。
 * 这里统一归一化，两种写法都吃得下，避免部署时手改连接串改出错。
 *
 * <p>池子刻意调小（默认 4）：Vercel 容器是每个实例一个 JVM，
 * Neon 免费版连接数有限，池子开大只会互相抢连接。
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    /**
     * 解析后的连接目标。
     * {@code configured=false} 表示环境里根本没给连接串——这和「给了但连不上」是两件事，
     * 前者允许用 {@code app.db.optional=true} 跳过迁移先把容器跑起来。
     */
    public record DbTarget(boolean configured, String jdbcUrl, String username, String password,
                           String host, String database) {

        static DbTarget none() {
            return new DbTarget(false, "", "", "", "", "");
        }
    }

    @Bean
    public DbTarget dbTarget(@Value("${DATABASE_URL:}") String databaseUrl,
                             @Value("${DB_URL:}") String dbUrl,
                             @Value("${DB_USER:}") String dbUser,
                             @Value("${DB_PASSWORD:}") String dbPassword) {
        DbTarget target = parse(databaseUrl, dbUrl, dbUser, dbPassword);
        if (target.configured()) {
            log.info("数据库目标：{} / {}（用户 {}）", target.host(), target.database(), target.username());
        } else {
            log.warn("未检测到数据库连接串：DATABASE_URL 与 DB_URL 都为空");
        }
        return target;
    }

    @Bean
    @Primary
    public DataSource dataSource(DbTarget target,
                                 @Value("${app.db.pool-size:4}") int poolSize,
                                 @Value("${app.db.optional:false}") boolean optional) {
        if (!target.configured() && !optional) {
            // 提前炸掉，比启动成功但每个请求 500 好排查
            throw new IllegalStateException(
                    "未配置数据库：请设置 DATABASE_URL（Neon 连接串）或 DB_URL/DB_USER/DB_PASSWORD；"
                            + "只想先验证容器能否启动时，可设 DB_OPTIONAL=true");
        }

        HikariConfig cfg = new HikariConfig();
        // 未配置且允许降级时，用一个连不上的占位地址：池子不预热，请求才会报错
        cfg.setJdbcUrl(target.configured() ? target.jdbcUrl() : "jdbc:postgresql://127.0.0.1:5432/postgres");
        if (target.configured()) {
            cfg.setUsername(target.username());
            cfg.setPassword(target.password());
        }
        cfg.setPoolName("code-explain-pool");
        cfg.setMaximumPoolSize(poolSize);
        cfg.setMinimumIdle(0);
        cfg.setConnectionTimeout(10_000);
        cfg.setIdleTimeout(60_000);
        cfg.setMaxLifetime(10 * 60_000);
        // >0 时启动即取连接做校验；未配置数据库的场景不能这么做
        cfg.setInitializationFailTimeout(target.configured() ? 10_000 : -1);
        return new HikariDataSource(cfg);
    }

    // ---------------------------------------------------------------- 解析

    static DbTarget parse(String databaseUrl, String dbUrl, String dbUser, String dbPassword) {
        String raw = firstNonBlank(databaseUrl, dbUrl);
        if (raw == null) {
            return DbTarget.none();
        }
        String trimmed = raw.trim();
        // 已经是 JDBC 写法：可能把账号密码塞在 query 里，抽出来交给 Hikari
        if (trimmed.startsWith("jdbc:postgresql://")) {
            return fromJdbcUrl(trimmed, dbUser, dbPassword);
        }
        // postgres:// 或 postgresql://
        if (trimmed.startsWith("postgres://") || trimmed.startsWith("postgresql://")) {
            return fromUri(trimmed);
        }
        // 兜底：当成 JDBC URL 用，至少不会连到错的地方
        log.warn("无法识别的数据库连接串格式，按 JDBC URL 直接使用");
        return new DbTarget(true, trimmed, dbUser, dbPassword, "", "");
    }

    private static DbTarget fromUri(String raw) {
        URI uri = URI.create(raw);
        String userInfo = uri.getUserInfo();
        String username = "";
        String password = "";
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            if (colon >= 0) {
                username = decode(userInfo.substring(0, colon));
                password = decode(userInfo.substring(colon + 1));
            } else {
                username = decode(userInfo);
            }
        }

        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() > 0) {
            jdbc.append(':').append(uri.getPort());
        }
        String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/postgres" : uri.getPath();
        jdbc.append(path);

        List<String> params = new ArrayList<>();
        String sslmode = null;
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                if (pair.isBlank()) {
                    continue;
                }
                String key = pair.split("=", 2)[0];
                // 账号密码改由 Hikari 传递；channel_binding 是 libpq 专有参数，PG JDBC 不认
                if ("user".equals(key) || "password".equals(key) || "channel_binding".equals(key)) {
                    continue;
                }
                if ("sslmode".equals(key)) {
                    sslmode = pair.split("=", 2).length > 1 ? pair.split("=", 2)[1] : null;
                }
                params.add(pair);
            }
        }
        // Neon 强制 SSL，没写就补上，否则握手会被拒
        if (sslmode == null) {
            params.add("sslmode=require");
        }
        if (!params.isEmpty()) {
            jdbc.append('?').append(String.join("&", params));
        }

        String db = path.startsWith("/") ? path.substring(1) : path;
        return new DbTarget(true, jdbc.toString(), username, password, nullToEmpty(uri.getHost()), db);
    }

    private static DbTarget fromJdbcUrl(String jdbcUrl, String fallbackUser, String fallbackPass) {
        String username = fallbackUser == null ? "" : fallbackUser;
        String password = fallbackPass == null ? "" : fallbackPass;
        String url = jdbcUrl;

        int q = jdbcUrl.indexOf('?');
        if (q < 0) {
            return new DbTarget(true, url, username, password, hostOf(jdbcUrl), dbOf(jdbcUrl));
        }

        List<String> kept = new ArrayList<>();
        for (String pair : jdbcUrl.substring(q + 1).split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] kv = pair.split("=", 2);
            String key = kv[0];
            String value = kv.length > 1 ? decode(kv[1]) : "";
            if ("user".equals(key)) {
                if (username.isEmpty()) {
                    username = value;
                }
                continue;
            }
            if ("password".equals(key)) {
                if (password.isEmpty()) {
                    password = value;
                }
                continue;
            }
            kept.add(pair);
        }
        url = jdbcUrl.substring(0, q) + (kept.isEmpty() ? "" : "?" + String.join("&", kept));
        return new DbTarget(true, url, username, password, hostOf(jdbcUrl), dbOf(jdbcUrl));
    }

    private static String hostOf(String jdbcUrl) {
        try {
            String rest = jdbcUrl.substring("jdbc:postgresql://".length());
            int slash = rest.indexOf('/');
            String hostPort = slash < 0 ? rest : rest.substring(0, slash);
            int colon = hostPort.indexOf(':');
            return colon < 0 ? hostPort : hostPort.substring(0, colon);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String dbOf(String jdbcUrl) {
        try {
            String rest = jdbcUrl.substring("jdbc:postgresql://".length());
            int slash = rest.indexOf('/');
            if (slash < 0) {
                return "";
            }
            String db = rest.substring(slash + 1);
            int q = db.indexOf('?');
            return q < 0 ? db : db.substring(0, q);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
