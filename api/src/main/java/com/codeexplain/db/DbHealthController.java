package com.codeexplain.db;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.codeexplain.config.DataSourceConfig.DbTarget;

/**
 * 数据库连通性自检。
 *
 * <p>M1 的验收端点：能返回 schema 版本和表清单，才说明「连接串正确 + 迁移已生效 + 有读权限」。
 * 出问题时把根因原样带出来（不吞异常），否则部署时只能靠猜。
 */
@RestController
@RequestMapping("/api/db")
public class DbHealthController {

    private static final Logger log = LoggerFactory.getLogger(DbHealthController.class);

    private final JdbcTemplate jdbc;
    private final DbTarget target;

    public DbHealthController(JdbcTemplate jdbc, DbTarget target) {
        this.jdbc = jdbc;
        this.target = target;
    }

    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> ping() {
        if (!target.configured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "ok", false,
                    "error", "数据库未配置：DATABASE_URL 与 DB_URL 都为空"
            ));
        }

        long started = System.nanoTime();
        try {
            String server = jdbc.queryForObject("select current_setting('server_version')", String.class);
            long latency = (System.nanoTime() - started) / 1_000_000;

            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "host", target.host(),
                    "database", target.database(),
                    "latencyMs", latency,
                    "server", "PostgreSQL " + server,
                    "schemaVersion", schemaVersion(),
                    "tables", tables(),
                    "counts", Map.of(
                            "users", count("users"),
                            "canvases", count("canvases"),
                            "questions", count("questions")
                    )
            ));
        } catch (DataAccessException e) {
            Throwable root = e.getMostSpecificCause();
            log.error("数据库连通性检查失败：{}", root.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "ok", false,
                    "host", target.host(),
                    "database", target.database(),
                    "error", root.getClass().getSimpleName() + ": " + root.getMessage()
            ));
        }
    }

    /** 当前已应用的最高迁移版本；迁移没跑过时返回 null 而不是抛错 */
    private String schemaVersion() {
        try {
            List<String> versions = jdbc.queryForList(
                    "select version from flyway_schema_history where success order by installed_rank desc limit 1",
                    String.class);
            return versions.isEmpty() ? null : versions.get(0);
        } catch (DataAccessException e) {
            return null;
        }
    }

    private List<String> tables() {
        return jdbc.queryForList(
                "select tablename from pg_tables where schemaname = 'public' order by tablename",
                String.class);
    }

    private long count(String table) {
        try {
            Long value = jdbc.queryForObject("select count(*) from " + table, Long.class);
            return value == null ? 0L : value;
        } catch (DataAccessException e) {
            return -1L;
        }
    }
}
