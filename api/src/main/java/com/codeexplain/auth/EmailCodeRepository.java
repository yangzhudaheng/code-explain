package com.codeexplain.auth;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** email_codes 表读写：发码、频控计数、一次性消费。 */
@Repository
public class EmailCodeRepository {

    private final JdbcTemplate jdbc;

    public EmailCodeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 返回新行 id：发信失败时调用方要靠它把这条记录删掉，否则用户被 60 秒冷却卡住。 */
    public UUID insert(String email, String codeHash, String purpose, Instant expiresAt, String ip) {
        return jdbc.queryForObject(
                "insert into email_codes (email, code_hash, purpose, expires_at, ip) "
                        + "values (?, ?, ?, ?, ?) returning id",
                UUID.class, email, codeHash, purpose,
                OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC), ip);
    }

    public void deleteById(UUID id) {
        jdbc.update("delete from email_codes where id = ?", id);
    }

    /** 该邮箱最近一次发码时间，用于 60 秒冷却。 */
    public Instant lastSentAt(String email) {
        List<Instant> rows = jdbc.query(
                "select created_at from email_codes where lower(email) = lower(?) "
                        + "order by created_at desc limit 1",
                (rs, i) -> rs.getObject(1, OffsetDateTime.class).toInstant(), email);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 同一 IP 最近 24 小时发码次数，用于每日上限。 */
    public int countByIpLastDay(String ip) {
        Integer n = jdbc.queryForObject(
                "select count(*) from email_codes where ip = ? and created_at > now() - interval '24 hours'",
                Integer.class, ip);
        return n == null ? 0 : n;
    }

    /**
     * 取该邮箱最近一条未消费的码。
     *
     * <p>没按 expires_at 过滤：过期与否要交给调用方判断，否则「过期」和「不存在」
     * 无法区分，用户会收到错误的提示（说他没申请过验证码）。
     */
    public List<CodeRow> latestActive(String email, String purpose) {
        return jdbc.query(
                "select id, code_hash, expires_at, attempts from email_codes "
                        + "where lower(email) = lower(?) and purpose = ? and consumed_at is null "
                        + "order by created_at desc limit 1",
                (rs, i) -> new CodeRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("code_hash"),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("attempts")),
                email, purpose);
    }

    public void incrementAttempts(UUID id) {
        jdbc.update("update email_codes set attempts = attempts + 1 where id = ?", id);
    }

    public void markConsumed(UUID id) {
        jdbc.update("update email_codes set consumed_at = now() where id = ?", id);
    }

    /** 顺带做一次懒清理：删掉一天前就过期的行，避免表无限增长。 */
    public int purgeExpired(Duration olderThan) {
        return jdbc.update("delete from email_codes where expires_at < ?",
                OffsetDateTime.ofInstant(Instant.now().minus(olderThan), ZoneOffset.UTC));
    }

    public record CodeRow(UUID id, String codeHash, Instant expiresAt, int attempts) {
    }
}
