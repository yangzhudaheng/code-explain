package com.codeexplain.qa;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * answers 表读写。
 *
 * <p>一条 question 可以有多条 answer（重答历史）。表里 status 走
 * {@code streaming → done|error}，中途落库是为了「用户关掉页面时别丢已经生成的内容」。
 */
@Repository
public class AnswerRepository {

    private final JdbcTemplate jdbc;

    public AnswerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(UUID questionId, String provider, String model) {
        return jdbc.queryForObject("""
                insert into answers (question_id, status, provider, model)
                values (?, 'streaming', ?, ?)
                returning id
                """, UUID.class, questionId, provider, model);
    }

    /** 流式过程中定期落库：进程被平台掐掉时，用户至少还能看到半段答案。 */
    public void updateContent(UUID id, String content) {
        jdbc.update("update answers set content = ? where id = ?", content, id);
    }

    public void finish(UUID id, String content, String model, Integer promptTokens, Integer completionTokens) {
        jdbc.update("""
                update answers
                   set content = ?, status = 'done', model = coalesce(?, model),
                       prompt_tokens = ?, completion_tokens = ?
                 where id = ?
                """, content, model, promptTokens, completionTokens, id);
    }

    public void fail(UUID id, String partialContent, String error) {
        jdbc.update("""
                update answers set content = ?, status = 'error', error = ? where id = ?
                """, partialContent, error, id);
    }

    public List<Row> byQuestion(UUID questionId) {
        return jdbc.query("""
                select id, content, status, provider, model, prompt_tokens, completion_tokens, error, created_at
                  from answers where question_id = ? order by created_at desc
                """,
                (rs, i) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getString("content"),
                        rs.getString("status"),
                        rs.getString("provider"),
                        rs.getString("model"),
                        (Integer) rs.getObject("prompt_tokens"),
                        (Integer) rs.getObject("completion_tokens"),
                        rs.getString("error"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()),
                questionId);
    }

    public record Row(UUID id, String content, String status, String provider, String model,
                      Integer promptTokens, Integer completionTokens, String error, Instant createdAt) {
    }
}
