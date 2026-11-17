package com.codeexplain.canvas;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.codeexplain.canvas.CanvasDtos.CanvasSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 画布表读写。
 *
 * <p>所有查询都带 {@code user_id = ?} 与 {@code deleted_at is null}：越权访问
 * 统一表现为「不存在」，不泄露别人的画布 id 是否存在。
 */
@Repository
public class CanvasRepository {

    /** 列表只取统计量，不取 data：几十个画布的完整代码一次性拉回来会很慢。 */
    private static final String LIST_SQL = """
            select id, title, version, updated_at,
                   case when jsonb_typeof(data -> 'blocks') = 'array'
                        then jsonb_array_length(data -> 'blocks') else 0 end as block_count,
                   case when jsonb_typeof(data -> 'questions') = 'array'
                        then jsonb_array_length(data -> 'questions') else 0 end as question_count
            from canvases
            where user_id = ? and deleted_at is null
            order by updated_at desc, created_at desc
            """;

    private static final String DETAIL_SQL = """
            select id, title, data::text as data_json, version, created_at, updated_at
            from canvases
            where id = ? and user_id = ? and deleted_at is null
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CanvasRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static final RowMapper<CanvasSummary> SUMMARY_MAPPER = (rs, i) -> new CanvasSummary(
            rs.getObject("id", UUID.class),
            rs.getString("title"),
            rs.getInt("version"),
            instant(rs, "updated_at"),
            rs.getInt("block_count"),
            rs.getInt("question_count"));

    private RowMapper<CanvasRow> detailMapper() {
        return (rs, i) -> new CanvasRow(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                readJson(rs.getString("data_json")),
                rs.getInt("version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    /** 库里写的永远是合法 jsonb，解析失败只可能是手工改库，兜底成空对象不让整页 500。 */
    private JsonNode readJson(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    public List<CanvasSummary> listByUser(UUID userId) {
        return jdbc.query(LIST_SQL, SUMMARY_MAPPER, userId);
    }

    public Optional<CanvasRow> find(UUID id, UUID userId) {
        List<CanvasRow> rows = jdbc.query(DETAIL_SQL, detailMapper(), id, userId);
        return rows.stream().findFirst();
    }

    public int countByUser(UUID userId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from canvases where user_id = ? and deleted_at is null",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    public UUID insert(UUID userId, String title, String dataJson) {
        return jdbc.queryForObject(
                "insert into canvases (user_id, title, data) values (?, ?, ?::jsonb) returning id",
                UUID.class, userId, title, dataJson);
    }

    /**
     * 乐观锁写入：{@code version = ?} 是唯一能挡住「两个标签页互相覆盖」的地方。
     *
     * @return 受影响行数，0 表示版本不匹配或画布已被删除
     */
    public int update(UUID id, UUID userId, int baseVersion, String title, String dataJson) {
        return jdbc.update("""
                update canvases
                   set title = ?, data = ?::jsonb, version = version + 1, updated_at = now()
                 where id = ? and user_id = ? and deleted_at is null and version = ?
                """, title, dataJson, id, userId, baseVersion);
    }

    /** 软删除：问答和回答还挂在下面，真删会连带丢掉用户的问题记录。 */
    public int softDelete(UUID id, UUID userId) {
        return jdbc.update(
                "update canvases set deleted_at = now() where id = ? and user_id = ? and deleted_at is null",
                id, userId);
    }

    public record CanvasRow(UUID id,
                            String title,
                            JsonNode data,
                            int version,
                            Instant createdAt,
                            Instant updatedAt) {
    }
}
