package com.codeexplain.qa;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * questions 表读写。
 *
 * <p>这里只存「调用记录」，不参与渲染：画布上的问答仍然是快照（{@code canvases.data}）的一部分。
 * 两者靠 {@code client_id} 对齐——快照里的 q_xxx ↔ 这条记录。
 */
@Repository
public class QuestionRepository {

    private final JdbcTemplate jdbc;

    public QuestionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按 (canvas_id, client_id) 幂等写入。
     *
     * <p>为什么用 upsert 而不是 insert：前端是「先本地建问答、再异步落库」，
     * 网络抖动或用户重试都会让同一个 client_id 到达两次。第二次若直接 insert，
     * 要么被唯一索引拒绝（用户看到报错），要么产生两条记录（回答对不上问题）。
     * upsert 让重复提交变成「更新同一行」，这是最符合直觉的结果。
     */
    public UUID upsert(UUID canvasId, String clientId, String blockId, String anchorJson, String text) {
        return jdbc.queryForObject("""
                insert into questions (canvas_id, block_id, anchor, text, client_id)
                values (?, ?, ?::jsonb, ?, ?)
                on conflict (canvas_id, client_id) where client_id is not null
                do update set block_id = excluded.block_id,
                              anchor = excluded.anchor,
                              text = excluded.text,
                              updated_at = now()
                returning id
                """, UUID.class, canvasId, blockId, anchorJson, text, clientId);
    }

    /** 连带校验归属：别人的 question id 与不存在都返回 empty，不泄露存在性。 */
    public Optional<Row> findOwned(UUID questionId, UUID userId) {
        List<Row> rows = jdbc.query("""
                select q.id, q.canvas_id, q.block_id, q.anchor::text as anchor_json, q.text, q.created_at
                  from questions q
                  join canvases c on c.id = q.canvas_id
                 where q.id = ? and c.user_id = ? and c.deleted_at is null
                """,
                (rs, i) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getObject("canvas_id", UUID.class),
                        rs.getString("block_id"),
                        rs.getString("anchor_json"),
                        rs.getString("text"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()),
                questionId, userId);
        return rows.stream().findFirst();
    }

    public record Row(UUID id, UUID canvasId, String blockId, String anchorJson, String text, Instant createdAt) {
    }
}
