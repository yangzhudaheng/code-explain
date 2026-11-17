package com.codeexplain.db;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 「没配数据库也要能启动」的兜底路径。
 *
 * <p>存在的理由很具体：Vercel 首次部署要先拿到后端域名，才能回填前端的 rewrites 目标；
 * 而拿到域名的前提是容器能起来。若数据库缺失就启动失败，就会卡死在「没域名 → 没法配前端」
 * 和「没数据库 → 容器起不来」之间。{@code DB_OPTIONAL=true} 就是打破这个死循环的开关。
 *
 * <p>它只允许「没配」，不允许「配了连不上」——后者是配置错误，必须启动失败。
 */
@SpringBootTest(properties = {"app.db.optional=true", "DATABASE_URL=", "DB_URL="})
@AutoConfigureMockMvc
class OptionalDatabaseTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("无数据库时容器仍能启动，健康端点给出明确的未配置提示")
    void startsWithoutDatabase() throws Exception {
        mvc.perform(get("/api/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mvc.perform(get("/api/db/ping"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").exists());
    }
}
