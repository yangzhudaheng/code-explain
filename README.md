# code-explain

把算法题解贴到一张无限画布上，选中任意代码片段提问，AI 的回答以可拖动的方框回挂到该片段——用带问题标签的箭头连过去。

> 详细的技术约束、交互规格与里程碑见 [AGENT.md](./AGENT.md)。改代码前先读它。

## 当前进度

- **M0 画布交互原型**：已完成（回答为模拟数据，无后端）
- **M0.5 容器冒烟包**：已完成（待部署到 Vercel 验证 Hobby 是否允许容器）
- **画布增强**：背景切换、回答框左右分侧、代码行号、语法高亮 已完成
- **代码块编辑**：编辑文本/文件名/语言、清空、删除代码块，改完自动重定位已有问答的锚点
- **M1 后端骨架**：Spring Boot 3 + Flyway 迁移（6 张表）+ Neon 连接串兼容 + `/api/db/ping` 自检
- **M2 注册登录**：图形码 + 邮件验证码（QQ SMTP 465）+ 频控 + BCrypt + httpOnly Cookie 会话
- **M3 画布持久化**：多画布增删改查 + 整体快照存 JSONB + `version` 乐观锁 + 前端防抖自动保存；
  刷新不丢内容，两标签页同改会提示冲突并让你选「重新加载 / 以我为准」；46 个后端测试全过
- M4~M6：未开始（下一步是接 AI 问答）

## 目录

```
web/    React + TypeScript + Vite 前端（画布本体）
api/    Spring Boot 3 / Java 21 后端（Flyway 迁移 + 数据库自检 + 冒烟端点）
```

## 本地运行前端

```bash
cd web
npm install
npm run dev     # http://localhost:5173
```

操作说明：

| 操作 | 说明 |
|---|---|
| 拖代码块标题栏 | 整块移动（字符不可单独拖动） |
| 选中代码任意片段 | 末尾出现蓝色加号，点击即可提问 |
| 拖动回答框标题栏 | 回答框移动，连线与问题标签跟随，代码不动 |
| 空格 + 拖拽 / 滚轮 | 平移画布 |
| Ctrl + 滚轮 | 以指针为中心缩放 |
| Ctrl + V | 直接粘贴代码成块 |
| 工具栏「整理」 | 解除锁定并重排所有回答框 |
| 工具栏「背景」 | 切换画布图案（点阵/方格/横线/纯色）与底色（米白/纯白/雾蓝/深灰），按画布独立保存 |
| 提问弹层「回答位置」 | 自动（按选中位置判断左右）/ 左侧 / 右侧 |
| 回答框标题栏 ← → | 把该回答框移到代码块的另一侧 |
| 代码块标题栏「编辑」 | 就地编辑代码：Tab 缩进 4 空格、Ctrl+Enter 保存、Esc 取消；可改文件名与语言 |
| 代码块标题栏「删除」 | 二次确认后删除整块，连带该块的提问与回答框 |
| 保存编辑后 | 已有问答按原文片段重新定位，高亮与箭头跟随；原文片段被删掉则标记「锚点失效」 |
| 侧栏画布项 双击 | 就地重命名，Enter 提交、Esc 取消 |
| 侧栏画布项 hover | 右侧出现 × ，二次确认后删除该画布（最后一个删掉会自动补一个空画布） |
| 画布改动 | 停手约 0.7 秒自动保存；侧栏底部显示「保存中 / 已保存 13:24 / 保存失败」 |
| 两个标签页同时改 | 后保存的一方提示「已在别处修改」，可选「重新加载」或「以我为准」 |

## 本地运行后端

需要 JDK 21 + Maven。数据库用 Neon 或本地 Postgres 都行。

```bash
cd api
export DATABASE_URL='postgres://user:pass@ep-xxx.neon.tech/neondb?sslmode=require'
mvn spring-boot:run       # http://localhost:8080
curl localhost:8080/api/db/ping
```

`/api/db/ping` 会返回连接耗时、PostgreSQL 版本、已应用的迁移版本和表清单——**部署后第一件事就是打开它**，
比看日志快。

只想验证「容器能不能起来」、还没接数据库时：

```bash
DB_OPTIONAL=true mvn spring-boot:run     # 跳过迁移，其余接口正常；/api/db/ping 返回 503 并说明原因
```

跑测试（不依赖本机装 Postgres，测试内部会拉起一个内嵌实例执行真实迁移）：

```bash
mvn test
```

## 认证接口（M2）

| 接口 | 说明 |
|---|---|
| `GET /api/auth/captcha` | 返回 `{id, image}`，`image` 是 PNG data URL，可直接塞进 `<img src>` |
| `POST /api/auth/email-code` | 需带图形码；同邮箱 60 秒冷却、同 IP 每日 10 次 |
| `POST /api/auth/register` | `{email, code, password, displayName?}`，成功即下发会话 Cookie |
| `POST /api/auth/login` | `{email, password}` |
| `POST /api/auth/logout` | 下发过期 Cookie |
| `GET /api/auth/me` | 需要会话；未登录返回 401 `UNAUTHORIZED` |
| `GET /api/auth/policy` | 冷却秒数、码有效期、次数上限，供前端展示 |

错误统一为 `{code, message, field?}`，`code` 取值见 `common/ErrorCode.java`。

**为什么图形码答案存服务端**：存 cookie 或签名里等于把答案交给攻击者。代价是多实例需要粘性会话，
本项目 Vercel 每实例单 JVM，暂不构成问题；将来横向扩容换成 Redis 即可。

## 画布接口（M3）

全部需要登录（Cookie 会话），别人的画布一律返回 404 而不是 403——不泄露 id 是否存在。

| 接口 | 说明 |
|---|---|
| `GET /api/canvases` | 列表，只带统计量：`{id, title, version, updatedAt, blockCount, questionCount}` |
| `POST /api/canvases` | `{title?, data?}`，返回 201 + 完整详情；不传 data 时给一个空画布骨架 |
| `GET /api/canvases/{id}` | 完整快照 `{id, title, data, version, createdAt, updatedAt}` |
| `PUT /api/canvases/{id}` | `{title, data, baseVersion}` 全量保存；版本不一致返回 409 |
| `DELETE /api/canvases/{id}` | 204，软删（`deleted_at` 置位，问答记录不丢） |

几个刻意的设计：

- **整体快照而非增量**：`data` 是 `{viewport, bg, blocks, questions}` 一整块 JSONB，一次 PUT 全量覆盖。
  画布就是「整块画布」，增量协议要维护 diff/patch 语义，收益远小于风险。
- **乐观锁**：`baseVersion` 必须等于库里的 `version` 才写得进去，成功后 `version + 1`。
  409 的响应不会写入任何数据（有测试专门断言这一点）。
- **上限与大小**：单账号最多 50 个画布（`CANVAS_MAX_PER_USER`），单次快照最多 2MB
  （`CANVAS_MAX_DATA_BYTES`，Vercel 函数请求体上限 4.5MB，留出包装余量）。
- **前端不存 `anchorRects`**：那是逐行像素矩形，几百 KB 且能由 `anchor` + 文本重算；
  刷新后 `CodeBlock` 会重新测量。存进快照既臃肿，换了字体还会错。

## 自动保存与冲突

前端 `store/persist.ts` 的规则：

1. 任何画布改动 → 标记 `pending`，700ms 防抖后保存（拖动代码块每秒几十次 setState 也只发一次请求）。
2. 保存成功 → 更新内存里的 `version`；保存期间又有新改动 → 只推版本号，内容等下一轮存。
3. 返回 409 → 状态变 `conflict`，**停止自动保存**（再写就是把别人的改动盖掉），
   由用户选「重新加载」（以服务端为准）或「以我为准」（先取最新版本号再写）。
4. 页面隐藏 / 关闭 → 立即 flush，关闭时用 `fetch(..., {keepalive:true})` 尽力发出去。
5. 登出 → 清空画布与加载锁，避免下一个账号看到上一个账号的内容。

## 部署（GitHub → Vercel）

### 1. 推代码

```bash
git remote add origin https://github.com/yangzhudaheng/code-explain.git
git push -u origin main
```

### 2. 部署后端容器（先做这一步）

1. Vercel → Add New → Project → 选 `code-explain` 仓库
2. **Root Directory** 选 `api`
3. Framework Preset 选 `Other`（Vercel 会自动识别 `Dockerfile.vercel`）
4. Deploy，等待镜像构建完成
5. 拿到域名后测试：
   - `https://<api 域名>/api/ping`
   - `https://<api 域名>/api/dev/stream`（验证 SSE 能撑多久）

> 若提示当前计划不支持容器，说明 Hobby 未开放该能力，把后端改挂 Zeabur / Render 免费版即可，前端不动。

### 3. 部署前端

1. Vercel → Add New → Project → 同一个仓库
2. **Root Directory** 选 `web`，Framework 自动识别为 Vite
3. 部署前把 `web/vercel.json` 里的 `<API_DOMAIN>` 换成第 2 步拿到的域名
4. Deploy

前后端同源后，浏览器只访问前端域名，`/api/*` 由 Vercel 边缘转发到后端——不需要配 CORS，也不会有第三方 Cookie 问题。

## 环境变量（后端，后续里程碑使用）

复制 `.env.example` 为 `.env` 仅用于本地，**不要提交**。

| 变量 | 用途 |
|---|---|
| `DATABASE_URL` | Neon 连接串，`postgres://` 原样粘贴即可（推荐，Vercel 集成会自动注入） |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | 备选写法，`DB_URL` 需为 `jdbc:postgresql://` |
| `DB_OPTIONAL` | 仅容器冒烟用：`true` 时无数据库也能启动；正常部署保持未设置 |
| `DB_POOL_SIZE` | 连接池大小，默认 4（Vercel 每实例一个 JVM，别调大） |
| `JWT_SECRET` | 会话签名密钥，`openssl rand -base64 48`；**少于 32 字节会拒绝启动** |
| `TOKEN_TTL` | 登录有效期，默认 `PT168H`（7 天） |
| `COOKIE_SECURE` | 线上必须 `true`；本地 `http://localhost` 必须 `false` |
| `SMTP_HOST` / `SMTP_PORT` | QQ 邮箱填 `smtp.qq.com` / `465`（云平台会封 25 端口） |
| `SMTP_USER` / `SMTP_PASS` | 邮箱全称 + **SMTP 授权码**（不是登录密码） |
| `SMTP_FROM` | 发件人，留空则同 `SMTP_USER` |
| `CAPTCHA_DEBUG` | 仅本地自动化测试用，打印图形码答案；生产必须 `false` |
| `CANVAS_MAX_PER_USER` | 单账号画布上限，默认 50 |
| `CANVAS_MAX_DATA_BYTES` | 单次保存快照上限，默认 2097152（2MB） |
| `APP_ENC_KEY` | M4 用：用户模型 API Key 的加密主密钥（AES-GCM，Base64） |
| `APP_BASE_URL` | 站点对外地址，用于邮件里的链接 |
