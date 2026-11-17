package com.codeexplain.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.codeexplain.config.DataSourceConfig.DbTarget;

/**
 * 连接串归一化的单元测试。
 *
 * <p>这段逻辑专门用来吃掉各家托管平台的差异（Neon 给 postgres://，JDBC 要 jdbc:postgresql://），
 * 出错的方式还特别隐蔽——连不上、或者连到错的库。所以逐种写法都钉一遍。
 */
class DataSourceConfigTest {

    @Test
    @DisplayName("Neon 的 postgres:// 连接串被转成 JDBC 并抽出账号密码")
    void parsesPostgresScheme() {
        DbTarget t = DataSourceConfig.parse(
                "postgres://alice:p%40ss@ep-cool-name-123456.ap-southeast-1.aws.neon.tech:5432/neondb?sslmode=require&channel_binding=require",
                null, null, null);

        assertThat(t.configured()).isTrue();
        assertThat(t.jdbcUrl())
                .startsWith("jdbc:postgresql://ep-cool-name-123456.ap-southeast-1.aws.neon.tech:5432/neondb")
                .contains("sslmode=require")
                // libpq 专有参数，PG JDBC 不认，必须剔除
                .doesNotContain("channel_binding");
        assertThat(t.username()).isEqualTo("alice");
        assertThat(t.password()).isEqualTo("p@ss");
        assertThat(t.host()).isEqualTo("ep-cool-name-123456.ap-southeast-1.aws.neon.tech");
        assertThat(t.database()).isEqualTo("neondb");
    }

    @Test
    @DisplayName("没写 sslmode 时自动补 require（Neon 不加密直接拒连）")
    void appendsSslMode() {
        DbTarget t = DataSourceConfig.parse(
                "postgresql://u:p@db.example.com/appdb", null, null, null);

        assertThat(t.jdbcUrl()).isEqualTo("jdbc:postgresql://db.example.com/appdb?sslmode=require");
    }

    @Test
    @DisplayName("JDBC 写法里塞的 user/password 参数被抽出来交给连接池")
    void extractsCredentialsFromJdbcUrl() {
        DbTarget t = DataSourceConfig.parse(null,
                "jdbc:postgresql://localhost:5432/postgres?user=postgres&password=secret&sslmode=disable",
                null, null);

        assertThat(t.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/postgres?sslmode=disable");
        assertThat(t.username()).isEqualTo("postgres");
        assertThat(t.password()).isEqualTo("secret");
    }

    @Test
    @DisplayName("DATABASE_URL 优先于 DB_URL")
    void databaseUrlWins() {
        DbTarget t = DataSourceConfig.parse(
                "postgres://a:b@primary/db1", "jdbc:postgresql://secondary/db2", "u", "p");

        assertThat(t.host()).isEqualTo("primary");
        assertThat(t.database()).isEqualTo("db1");
    }

    @Test
    @DisplayName("两个都没配时标记为未配置而不是抛异常")
    void missingConfigIsNotAnError() {
        DbTarget t = DataSourceConfig.parse("", null, null, null);

        assertThat(t.configured()).isFalse();
    }
}
