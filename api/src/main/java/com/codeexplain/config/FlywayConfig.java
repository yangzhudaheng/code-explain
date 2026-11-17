package com.codeexplain.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.codeexplain.config.DataSourceConfig.DbTarget;

/**
 * Flyway 启动策略。
 *
 * <p>默认必须迁移成功才允许启动——表结构不对时，服务跑起来只会返回一堆 500。
 *
 * <p>例外是 {@code app.db.optional=true}：这时允许「没配数据库」直接起来。
 * 用它的唯一场景是先用最小镜像验证 Vercel 能不能跑容器（拿域名），
 * 之后接上 Neon 再改成必需。注意只对「没配」放行，配了但连不上仍然启动失败。
 */
@Configuration
public class FlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy(DbTarget target,
                                                           @Value("${app.db.optional:false}") boolean optional) {
        return flyway -> {
            if (!target.configured()) {
                if (optional) {
                    log.warn("跳过数据库迁移：未配置连接串且 app.db.optional=true（仅用于容器冒烟）");
                    return;
                }
                throw new IllegalStateException("未配置数据库，无法执行迁移");
            }
            var result = flyway.migrate();
            log.info("Flyway 完成：本次应用 {} 个迁移，当前版本 {}",
                    result.migrationsExecuted, result.targetSchemaVersion);
        };
    }
}
