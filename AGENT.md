# AGENT.md — code-explain 项目开发规范

> 本文件是项目的**唯一权威约束文档**。任何人（包括 AI Agent）接手本项目，先读本文件，再动代码。
> 状态：`规划完成，等待用户下达「开始」指令。`
> 最后更新：2026-11-17

---

## 0. 一页速览

| 项 | 值 |
|---|---|
| 项目名 | code-explain |
| 一句话 | 把算法题解贴到无限画布上，选中任意代码片段提问，AI 回答以连线方框回挂到该片段 |
| 仓库 | `https://github.com/yangzhudaheng/code-explain`（待创建，私有） |
| 仓库所有者 | yangzhudaheng |
| 部署 | GitHub → Vercel（web 静态 + api 容器），公网 URL |
| 形态 | 浏览器多人可用，账号隔离 |
| 当前阻塞 | Vercel Hobby 是否开放容器（M0.5 验证）；域名（仅影响国内可达性） |

---

## 1. 不可协商的硬约束

以下每一条都是踩过坑换来的，**不得为了省事绕过**。

1. **代码块必须用 DOM 文本渲染，禁止用 Canvas 绘制。**
   Canvas 里的文字无法被浏览器原生选中，`window.getSelection()` 拿不到，整个「选中片段提问」的交互会直接崩掉。渲染结构是三层：DOM 节点层 + SVG 连线层 + CSS transform 视口层。
2. **代码块是原子节点。** 拖拽以整块为单位，字符不可单独拖动。拖拽把手 = 标题栏 + 内边距区域；正文区域留给文本选择。二者用 pointer 事件分区，不靠猜。
3. **锚点存语义坐标，不存像素。** `{blockId, startLine, startChar, endLine, endChar, quoteHash}`。代码块移动、缩放、改字号后，靠重算 Range 还原高亮，绝不存 x/y。
4. **密钥永不落地明文。**
   - 用户填的模型 API Key：AES-GCM 落库，接口回显只给 `sk-****3f8a` 掩码，任何接口不得返回明文。
   - 站点 SMTP 授权码、JWT 密钥、加密主密钥：只从环境变量读，**不进 Git、不进代码、不进日志**。
   - 用户曾在对话中明文发过 QQ SMTP 授权码，已要求其重置；若代码里出现该字符串，视为严重事故。
5. **禁止接收用户的 GitHub 账号密码。** 推仓库走「用户自己 push」或「细粒度 PAT / Deploy Key」，见第 8 节。
6. **模型 base-url 必须做 SSRF 防护。** 强制 https，拦截 `127.0.0.0/8`、`10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`、`169.254.0.0/16`、`::1`、`localhost`、`.internal`。用户能填 URL 就等于给了他一个内网探测器。
7. **不引入 CRDT / OT。** v1 是单人编辑，画布存整体快照 + 乐观锁版本号。实时协作不做。
8. **不引入重型 UI 框架。** 不装 Ant Design / MUI / Element。画布要的是像素级控制，组件库只会添乱。
9. **不动 `.workbuddy/`。** 那是工作区元数据，不是项目代码，已在 `.gitignore` 里。

---

## 2. 技术选型（已定，勿随意更换）

### 2.1 前端

| 关注点 | 选择 | 理由 |
|---|---|---|
| 框架 | React 18 + TypeScript + Vite | 生态标准，构建产物纯静态，Vercel 零配置 |
| 画布渲染 | DOM 节点层 + SVG 连线层 + CSS `transform: translate() scale()` | 见硬约束 1 |
| 状态 | Zustand | 轻，无 Provider 嵌套，适合画布这种高频更新 |
| 样式 | 原生 CSS + CSS 变量 | 不引 Tailwind，避免与画布内联样式冲突 |
| 代码高亮 | 先用 `white-space: pre` 纯文本，M6 再接 Shiki（**只着色，不改 DOM 结构**，否则锚点失效） | 高亮必须用 `<span>` 包裹且保持文本节点顺序连续 |
| 字体 | `JetBrains Mono` 自托管 woff2 | 不引 Google Fonts，国内会被墙 |

### 2.2 后端

| 关注点 | 选择 |
|---|---|
| 语言/框架 | Java 21 + Spring Boot 3.3.x，Maven 单模块 |
| 依赖 | `web`, `validation`, `data-jpa`, `flyway`, `caffeine`, `mail`, `spring-security-crypto`（只取 BCrypt，不引整个 Security 过滤器链） |
| 流式转发 | Java 21 虚拟线程 + `java.net.http.HttpClient` + `SseEmitter`（不引 WebFlux） |
| 数据库 | PostgreSQL（Neon 免费版，Vercel Marketplace 一键集成） |
| 迁移 | Flyway，`V1__init.sql` 起步，禁止手工改线上表 |
| 会话 | JWT HS256 放 **httpOnly + Secure + SameSite=Lax Cookie**（同源代理使 Cookie 可用，避免 localStorage 被 XSS 偷） |
| 部署 | `Dockerfile.vercel`，监听 `$PORT` |

### 2.3 外部服务

| 用途 | 方案 | 备注 |
|---|---|---|
| 注册人机校验 | 自研图形码（easy-captcha）+ 频控 | 抽象 `CaptchaProvider`，后续可换阿里云验证码 2.0 |
| 发信 | QQ 邮箱 SMTP，**465 SSL** | 25 端口平台封禁；备用方案为 HTTPS API 发信（Brevo/Resend） |
| AI | 用户自带 Key，预设 6 个 Provider | 不内置任何免费额度 |

---

## 3. 部署契约

```
GitHub (monorepo)
   ├── web/   ──→ Vercel Project #1（静态，Vite）
   └── api/   ──→ Vercel Project #2（容器，Dockerfile.vercel）

浏览器 ──→ web 域名/api/*  ──(rewrites)──→ api.vercel.app
浏览器 ──→ 其余路径 ──→ 静态资源
```

### 3.1 关键设计：用 rewrites 造同源

前端所有请求写**相对路径** `/api/xxx`。`web/vercel.json` 里配：

```json
{
  "rewrites": [
    { "source": "/api/(.*)", "destination": "https://<api-项目域名>/api/$1" }
  ]
}
```

收益：浏览器视角是同一个源 → **不需要 CORS，不需要 `SameSite=None`，不存在第三方 Cookie 被拦问题**。
代价：`<api-项目域名>` 依赖首次部署结果，属先有鸡后有蛋，处理顺序见 8.4。

### 3.2 环境变量清单（Vercel Project #2 配置）

| 变量 | 说明 |
|---|---|
| `DB_URL` `DB_USER` `DB_PASSWORD` | Neon 连接串，JDBC 格式 |
| `JWT_SECRET` | ≥32 字节随机串 |
| `APP_ENC_KEY` | AES-GCM 主密钥，Base64，32 字节 |
| `SMTP_HOST` `SMTP_PORT` `SMTP_USER` `SMTP_PASS` `SMTP_FROM` | 465 / SSL |
| `APP_BASE_URL` | 站点对外地址，用于邮件里的链接 |
| `APP_PROFILE` | `prod` |

`.env.example` 只放占位符，`.env` 必须进 `.gitignore`。

### 3.3 已知平台限制（写进代码设计，不要对抗它）

- 容器**无状态、无持久磁盘** → 所有数据落 Postgres，不写本地文件
- **无长驻线程** → 验证码过期靠 TTL 判断 + 定时懒删除，不用 `@Scheduled`
- **请求时长有上限** → SSE 超时降级为「一次性返回 + 前端逐字显示」
- **JVM 冷启动 3~8s** → 前端首屏给骨架屏，接口超时设 20s 并提示「服务正在唤醒」
- **容器是 beta** → M0.5 必须先用最小镜像验证可用性

---

## 4. 目录结构

```
code-explain/
├── AGENT.md
├── README.md
├── .gitignore
├── web/
│   ├── index.html
│   ├── vite.config.ts          # dev 下 /api 代理到 localhost:8080
│   ├── vercel.json             # rewrites
│   └── src/
│       ├── canvas/             # 画布核心，与业务无关
│       │   ├── Viewport.tsx    # 平移/缩放
│       │   ├── CodeBlock.tsx   # 原子代码块
│       │   ├── SelectionPlus.tsx
│       │   ├── ConnectorLayer.tsx
│       │   ├── AnswerBox.tsx
│       │   ├── anchors.ts      # 字符偏移 ↔ Range 互转
│       │   └── layout.ts       # 避让与自动整理
│       ├── pages/              # 登录/注册/画布列表
│       ├── api/                # fetch 封装
│       └── store/
└── api/
    ├── Dockerfile.vercel
    ├── pom.xml
    └── src/main/
        ├── java/com/codeexplain/
        │   ├── auth/           # 图形码、邮件码、登录
        │   ├── canvas/         # 画布 CRUD
        │   ├── qa/             # 问题、回答、SSE
        │   ├── ai/             # Provider 抽象、SSRF 校验、加密
        │   └── common/         # 异常、频控、JWT
        └── resources/
            ├── application.yml
            └── db/migration/V1__init.sql
```

---

## 5. 数据模型

结构化数据（代码块、位置）走 `canvases.data` JSONB；问答走关系表（便于查询、重答、审计）。

```sql
users(id uuid pk, email citext unique, password_hash, status, created_at, email_verified_at)
email_codes(id uuid, email, code_hash, purpose, expires_at, consumed_at, ip, attempts)
canvases(id uuid, user_id, title, data jsonb, version int default 0, created_at, updated_at, deleted_at)
questions(id uuid, canvas_id, block_id, anchor jsonb, text, box_x, box_y, pinned bool, created_at)
answers(id uuid, question_id, content text, status, model, prompt_tokens, completion_tokens, created_at)
user_ai_settings(user_id pk, provider, base_url, model, api_key_cipher, api_key_mask, extra jsonb, updated_at)
```

`canvases.data` 结构：

```json
{
  "viewport": { "x": 0, "y": 0, "zoom": 1 },
  "blocks": [
    {
      "id": "b_01",
      "x": 120, "y": 80,
      "width": 560,
      "title": "Solution.java",
      "language": "java",
      "content": "class Solution {\n  ...\n}"
    }
  ]
}
```

`anchor` 结构（**像素无关**）：

```json
{ "blockId": "b_01", "startLine": 3, "startChar": 4, "endLine": 5, "endChar": 18,
  "quote": "while (lo < hi) {", "quoteHash": "9f2c..." }
```

---

## 6. 画布交互规格（本项目的心脏，逐条实现）

### 6.1 视口

- `wheel` 平移；`Ctrl/Cmd + wheel` 以指针为锚点缩放，范围 `0.25 ~ 2.5`
- 空格 + 拖拽 = 手型平移
- 世界坐标 ↔ 屏幕坐标互转函数集中放 `Viewport.tsx`，组件内禁止自行换算

### 6.2 代码块

- 粘贴整段代码 → 生成一个块，宽默认 560，代码行高 20px、字号 13px
- 拖拽：`pointerdown` 命中标题栏或内边距 → 进入拖动；命中正文 → 交给浏览器做文本选择
- 拖动时 `user-select: none`，但**不改动文本节点**（改 DOM 会让已选中的 Range 失效）
- 块内滚动超过 60 行时，块内滚动条；横向上**不换行**，超宽则块内横向滚动

### 6.3 选中 → 加号 → 提问

1. 鼠标 `mouseup` 时读 `window.getSelection()`
2. 校验：非空、完全落在同一个块内、不是跨块选择
3. 取 `range.getClientRects()` 最后一行的右端 + 8px，放圆形 `+` 按钮（跟随视口缩放，保持固定屏幕尺寸）
4. 点击 `+` → 弹输入框（定位在按钮下方，靠近右边界时翻到左侧）
5. 提交后：算锚点 → `POST /api/canvases/{id}/questions` → 建回答框（loading 态）→ 开 SSE

### 6.4 高亮还原（关键实现）

锚点 → 高亮不用 `mark` 标签包裹文本，而是**在独立的 overlay 层画半透明矩形**：

1. 用 TreeWalker 按字符偏移定位到 Text 节点的 `(node, offset)`
2. 构造 `Range`，`getClientRects()` 拿到每行矩形
3. 矩形换算成块内坐标，画在 overlay 上，`pointer-events: none`

好处：DOM 文本结构永远不被修改，锚点不会因高亮而偏移；选中态和持久高亮可以共存。

### 6.5 连线

- 起点：锚点矩形区域**右边缘中点**（世界坐标）；终点：回答框**左边缘中点**
- 三次贝塞尔，控制点水平外推 `dx = max(40, distance * 0.4)`
- 箭头指向代码一侧（`marker-end` 在起点）
- 问题标签：曲线 `t = 0.5` 处取点，最长 18 字截断加 `…`，背后垫白色圆角矩形（`rx=9`）避免压字
- 层级：连线层 `z-index: 10` < 代码块/回答框 `z-index: 20` → **天然被块遮住，不会盖住代码**

### 6.6 单侧布局与避让

- 回答框 `x` 锁定：`block.x + block.width + 96`，形成右侧回答列
- 初始 `y`：锚点中心 y − 24
- 避让：同列按目标 y 排序后贪心下推，`y = max(desiredY, prevBottom + 16)`
- `pinned = true` 的框不参与下推，其他框绕开它
- 若 `x` 处与右侧其他代码块重叠，整列右移到冲突块右侧 + 96（最多迭代 8 次防死循环）

### 6.7 同一片段多问题

- 2~3 个：共用同一锚点，连线在起点处按 `±12°` 扇形错开，避免完全重合
- ≥4 个：折叠为堆叠组，只画 1 条线 + 一枚 `还有 N 个` 的胶囊；点击展开扇形
- 连线密度抑制：`opacity = clamp(1 - 0.12 * (n - 1), 0.35, 1)`
- hover 单条线时其余线降到 0.2，代码块高亮对应片段

### 6.8 一键整理

- 触发：工具栏「整理」按钮
- 动作：按代码块分组 → 重算列 x → 按锚点 y 排序 → 贪心避让 → 仅跳过 `pinned` 框
- 动画：`transform` 过渡 220ms，期间禁止拖拽
- 支持「撤销上一次整理」（栈深度 20）

### 6.9 回答框

- 可拖动（只动框 + 连线 + 标签，代码绝不动）；拖动结束写回 `box_x/box_y`
- 可折叠为 1 行标题；可删除（连带连线）
- 内容支持 Markdown 渲染（只渲染 `code / bold / list / inline code`，不用 `dangerouslySetInnerHTML` 直接吞全文）
- 状态：`loading / streaming / done / error / no-key`

---

## 7. API 契约

```
POST   /api/auth/captcha                    → {captchaId, imageBase64}
POST   /api/auth/email-code                 {email, captchaId, captchaInput}
POST   /api/auth/register                   {email, code, password}
POST   /api/auth/login                      {email, password} → Set-Cookie
POST   /api/auth/logout
GET    /api/me                              → {id, email}

GET    /api/canvases                        → [{id, title, updatedAt}]
POST   /api/canvases                        {title}
PATCH  /api/canvases/{id}                   {title}
DELETE /api/canvases/{id}
GET    /api/canvases/{id}                   → {data, version}
PUT    /api/canvases/{id}/data              {data, version}  // 乐观锁，版本冲突返回 409

POST   /api/canvases/{id}/questions         {blockId, anchor, text} → {questionId, boxX, boxY}
GET    /api/questions/{id}/answer           SSE: event=delta|done|error
PATCH  /api/questions/{id}                  {boxX, boxY, pinned}
DELETE /api/questions/{id}

GET    /api/me/ai-settings                  → {provider, baseUrl, model, apiKeyMask}
PUT    /api/me/ai-settings                  {provider, baseUrl, model, apiKey?}
POST   /api/me/ai-settings/test             → {ok, latencyMs, error?}
```

统一错误体：`{ "code": "RATE_LIMITED", "message": "请 60 秒后再试" }`，前端按 `code` 出中文提示。

---

## 8. Git 与部署流程

### 8.1 用户采用方式 A：自己 push

Agent 只做本地提交，推送命令交给用户执行：

```bash
cd <repo 目录>
git init -b main
git add -A
git commit -m "chore: init project skeleton"
git remote add origin https://github.com/yangzhudaheng/code-explain.git
git push -u origin main
```

用户首次 push 时由 GitHub 自身弹窗/凭据管理器完成认证，Agent 全程不接触账号密码。

### 8.2 提交规范

`<type>: <中文简述>`，type ∈ `feat|fix|refactor|chore|docs|perf|test`。一个提交一件事，不混杂重构与功能。

### 8.3 分支

`main` 为部署分支；开发用 `feat/xxx`，完成后合并。小改动可直接提交 `main`（单人项目）。

### 8.4 首次部署顺序（有先有后，不能颠倒）

1. 先部署 `api/` 项目 → 拿到生产域名（如 `code-explain-api.vercel.app`）
2. 回填 `web/vercel.json` 的 rewrite 目标 → 部署 `web/`
3. Vercel 项目 #2 配齐 3.2 全部环境变量，重新部署使其生效
4. 跑冒烟测试：注册 → 收码 → 登录 → 建画布 → 提问 → 收流

---

## 9. 里程碑与验收

| 里程碑 | 内容 | 验收标准 |
|---|---|---|
| **M0.5 可行性冒烟**（最高优先） | 最小 Spring Boot 容器 + `Dockerfile.vercel` 推 Vercel | 公网可访问 `/actuator/health`；确认 Hobby 允许容器；记录冷启动耗时与 SSE 是否可跑满 60s |
| **M0 交互原型** | 纯前端单页，无后端，回答用假数据 | 粘贴代码成块、整块拖动、选中出加号、连线带问题标签、回答框避让、一键整理，全部可交互 |
| **M1 后端骨架** | Spring Boot + Flyway + 本地 docker-compose(Postgres) | 本地起得来，`/actuator/health` 通，V1 建表成功 |
| **M2 认证** | 图形码、邮件码、频控、登录、Cookie 会话 | 同邮箱 60s 冷却、同 IP 日限 10 次生效；错误码齐全 |
| **M3 画布持久化** | 多画布 CRUD、快照保存、乐观锁 | 刷新不丢内容；两标签页同改触发 409 并提示 |
| **M4 AI 问答** | Provider 抽象、SSRF 校验、加密存储、SSE | 6 个预设可切换；Key 掩码回显；内网地址被拒 |
| **M5 上线** | GitHub push → Vercel 双项目 → 域名（可选） | 换一台电脑浏览器可完整走通全流程 |
| **M6 打磨** | 代码高亮、导出 PNG、性能虚拟化 | 200 个节点下拖动不掉帧 |

**顺序纪律**：M0.5 不过，不写业务代码。M0 手感没被确认，不做 M1。

---

## 10. 当前状态与待办

- [x] 需求拆解、选型定稿
- [x] 部署策略可行性判断（GitHub + Vercel 通）
- [ ] **等待用户下达「开始」**
- [ ] 用户重置 QQ SMTP 授权码（旧码已泄露）
- [ ] 用户注册 Neon 账号（GitHub 一键登录）
- [ ] 建 GitHub 私有仓库 `code-explain`（不勾 README，避免冲突）
- [ ] 域名（M5 前决定即可，仅影响国内访问）
- [ ] M0.5 验证 Vercel Hobby 容器可用性

---

## 11. 本地开发

```bash
# 前端（web/）
npm install && npm run dev            # http://localhost:5173，/api 代理到 8080

# 后端（api/）
docker compose up -d postgres          # 本地 Postgres
mvn spring-boot:run                    # http://localhost:8080

# 本地联调时验证码走日志
# 未配 SMTP 时，验证码打印到控制台（仅 dev profile 允许）
```

---

## 12. 给未来 Agent 的提醒

- 改画布相关代码前，**先跑一遍 M0 原型的交互**，确认没有回归
- 任何涉及锚点、DOM 结构、文本节点的改动，都必须验证「刷新后高亮位置仍然正确」
- 新增第三方依赖前先问：能不能 100 行自己写完？画布类项目最怕依赖臃肿
- 不要擅自把 JSONB 拆成关系表，也不要把问答塞进 JSONB，当前的切分是权衡后的结果
- 不确定的交互细节，**先做出来给用户点一下**，比写三页设计文档有用
