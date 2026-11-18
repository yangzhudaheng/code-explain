package com.codeexplain.auth;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** captcha_sessions 表读写：签发、一次性消费、懒清理。 */
@Repository
public class CaptchaRepository {

    private final JdbcTemplate jdbc;

    public CaptchaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String id, String answerHash, Instant expiresAt, String ip) {
        jdbc.update(
                "insert into captcha_sessions (id, answer_hash, expires_at, ip) values (?, ?, ?, ?)",
                id, answerHash, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC), ip);
    }

    /**
     * 原子消费：删掉这一行并把答案哈希带回来。
     *
     * <p>为什么用 {@code delete ... returning} 而不是「先查再删」：并发重放同一个 id 时，
     * 只有第一个请求能删到行，第二个拿到空集。这样「校验一次就销毁」由数据库保证，
     * 不依赖应用层的加锁（多实例部署时进程内锁本来就是无效的）。
     *
     * <p>注意删除条件只有 id，不带答案比对——答案错了也要销毁这张图，
     * 否则一个 id 能在 5 分钟内被反复试（4 位字符空间经不起暴力猜）。
     */
    public Consumed consume(String id) {
        List<Consumed> rows = jdbc.query(
                "delete from captcha_sessions where id = ? returning answer_hash, expires_at",
                (rs, i) -> new Consumed(
                        rs.getString("answer_hash"),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 懒清理：删掉过期一小时以上的行，避免表随刷新次数无限增长。 */
    public int purgeExpired(Duration olderThan) {
        return jdbc.update("delete from captcha_sessions where expires_at < ?",
                OffsetDateTime.ofInstant(Instant.now().minus(olderThan), ZoneOffset.UTC));
    }

    public record Consumed(String answerHash, Instant expiresAt) {
    }
}
