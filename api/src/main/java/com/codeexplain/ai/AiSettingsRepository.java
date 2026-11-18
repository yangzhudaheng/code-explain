package com.codeexplain.ai;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** user_ai_settings 表读写。一个用户只有一条配置（主键就是 user_id）。 */
@Repository
public class AiSettingsRepository {

    private final JdbcTemplate jdbc;

    public AiSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Row> find(UUID userId) {
        List<Row> rows = jdbc.query("""
                select provider, base_url, model, api_key_cipher, api_key_mask, updated_at
                  from user_ai_settings where user_id = ?
                """,
                (rs, i) -> new Row(
                        rs.getString("provider"),
                        rs.getString("base_url"),
                        rs.getString("model"),
                        rs.getBytes("api_key_cipher"),
                        rs.getString("api_key_mask"),
                        rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()),
                userId);
        return rows.stream().findFirst();
    }

    /**
     * 写入配置。
     *
     * @param cipher 为 null 表示「沿用旧 Key」——用户只想改模型名时不该被迫再输一次 Key
     */
    public void upsert(UUID userId, String provider, String baseUrl, String model,
                       byte[] cipher, String mask) {
        if (cipher == null) {
            jdbc.update("""
                    insert into user_ai_settings (user_id, provider, base_url, model)
                    values (?, ?, ?, ?)
                    on conflict (user_id) do update
                       set provider = excluded.provider,
                           base_url = excluded.base_url,
                           model = excluded.model,
                           updated_at = now()
                    """, userId, provider, baseUrl, model);
            return;
        }
        jdbc.update("""
                insert into user_ai_settings (user_id, provider, base_url, model, api_key_cipher, api_key_mask)
                values (?, ?, ?, ?, ?, ?)
                on conflict (user_id) do update
                   set provider = excluded.provider,
                       base_url = excluded.base_url,
                       model = excluded.model,
                       api_key_cipher = excluded.api_key_cipher,
                       api_key_mask = excluded.api_key_mask,
                       updated_at = now()
                """, userId, provider, baseUrl, model, cipher, mask);
    }

    /** 用户主动清除 Key：连同掩码一起删，界面上不该再显示一个已经失效的尾巴。 */
    public void clearKey(UUID userId) {
        jdbc.update("""
                update user_ai_settings
                   set api_key_cipher = null, api_key_mask = null, updated_at = now()
                 where user_id = ?
                """, userId);
    }

    public record Row(String provider, String baseUrl, String model,
                      byte[] apiKeyCipher, String apiKeyMask, Instant updatedAt) {
    }
}
