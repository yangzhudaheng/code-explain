package com.codeexplain.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 用户表读写。显式 SQL，不用 JPA（见 AGENT.md 的选型记录）。 */
@Repository
public class UserRepository {

    private static final RowMapper<AuthUser> MAPPER = UserRepository::mapRow;

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static AuthUser mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AuthUser(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("display_name"),
                rs.getObject("email_verified_at", java.time.OffsetDateTime.class) != null,
                rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant());
    }

    /** 邮箱按大小写不敏感匹配，和 users_email_lower_uidx 索引保持一致。 */
    public Optional<AuthUser> findByEmail(String email) {
        List<AuthUser> rows = jdbc.query(
                "select id, email, password_hash, display_name, email_verified_at, created_at "
                        + "from users where lower(email) = lower(?)",
                MAPPER, email);
        return rows.stream().findFirst();
    }

    public Optional<AuthUser> findById(UUID id) {
        List<AuthUser> rows = jdbc.query(
                "select id, email, password_hash, display_name, email_verified_at, created_at "
                        + "from users where id = ?",
                MAPPER, id);
        return rows.stream().findFirst();
    }

    public boolean existsByEmail(String email) {
        Boolean exists = jdbc.queryForObject(
                "select exists(select 1 from users where lower(email) = lower(?))",
                Boolean.class, email);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 插入新用户。并发注册同一邮箱时靠唯一索引兜底，
     * 调用方需捕获 {@code DuplicateKeyException} 并转成 EMAIL_TAKEN。
     */
    public AuthUser insert(String email, String passwordHash, String displayName, Instant verifiedAt) {
        return jdbc.queryForObject(
                "insert into users (email, password_hash, display_name, email_verified_at) "
                        + "values (?, ?, ?, ?) "
                        + "returning id, email, password_hash, display_name, email_verified_at, created_at",
                MAPPER, email, passwordHash, displayName,
                verifiedAt == null ? null : java.time.OffsetDateTime.ofInstant(verifiedAt, java.time.ZoneOffset.UTC));
    }

    /** 账号视图：绝不包含 password_hash 的对外投影由 DTO 负责。 */
    public record AuthUser(UUID id,
                           String email,
                           String passwordHash,
                           String displayName,
                           boolean emailVerified,
                           Instant createdAt) {
    }
}
