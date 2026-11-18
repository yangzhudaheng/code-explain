# AGENT.md — code-explain 项目开发规范

> 本文件是项目的**唯一权威约束文档**。任何人（包括 AI Agent）接手本项目，先读本文件，再动代码。
> 状态：`M0~M6 已完成（导出 PNG 已落地；性能虚拟化未做），线上跑通注册→画布→AI 流式问答→导出。`
> 最后更新：2026-11-18

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
| 当前阻塞 | 无（Vercel 容器已验证可用；域名污染只影响国内直连，需代理或换国内服务器） |

---

## 1. 不可协商的硬约束

以下每一条都是踩过坑换来的，**不得为了省事绕过**。

1. **代码块必须用 DOM 文本渲染，禁止用 Canvas 绘制。**
   Canvas 里的文字无法被浏览器原生选中，`window.getSelection()` 拿不到，整个「选中片段提问」的交互会直接崩掉。渲染结构是三层：DOM 节点层 + SVG 连线层 + CSS transform 视口层。
2. **代码块是原子节点。** 拖拽以整块为单位，字符不可单独拖动。拖拽把手 = 标题栏 + 内边距区域；正文区域留给文本选择。二者用 pointer 事件分区，不靠猜。
3. **锚点存语义坐标，不存像素。** `{blockId, startOffset, endOffset, startLine, startChar, endLine, endChar, quote}`。代码块移动、缩放、改字号后，靠重算 Range 还原高亮，绝不存 x/y。
4. **`<pre>` 内的文本必须保持"一字不差、一行不拆"。** 语法高亮只能加 `<span>` 包裹，行号必须是 `<pre>` 的兄弟节点，**禁止把代码按行拆成 `<div>`**。一旦拆分，`Range.toString()` 不跨块补换行，所有字符偏移整体错位，锚点体系立刻报废。
5. **密钥永不落地明文。**
   - 用户填的模型 API Key：AES-GCM 落库，接口回显只给 `sk-****3f8a` 掩码，任何接口不得返回明文。
   - 站点 SMTP 授权码、JWT 密钥、加密主密钥：只从环境变量读，**不进 Git、不进代码、不进日志**。
   - 用户曾在对话中明文发过 QQ SMTP 授权码，已要求其重置；若代码里出现该字符串，视为严重事故。
6. **禁止接收用户的 GitHub 账号密码。** 推仓库走「用户自己 push」或「细粒度 PAT / Deploy Key」，见第 8 节。
7. **模型 base-url 必须做 SSRF 防护。** 强制 https，拦截 `127.0.0.0/8`、`10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`、`169.254.0.0/16`、`::1`、`localhost`、`.internal`。用户能填 URL 就等于给了他一个内网探测器。
8. **不引入 CRDT / OT。** v1 是单人编辑，画布存整体快照 + 乐观锁版本号。实时协作不做。
9. **不引入重型 UI 框架。** 不装 Ant Design / MUI / Element。画布要的是像素级控制，组件库只会添乱。
10. **不动 `.workbuddy/`。** 那是工作区元数据，不是项目代码，已在 `.gitignore` 里。
11. **编辑代码后必须重定位锚点，禁止静默丢弃问答。**
    - 文本改动后按 `anchor.quote` 在新文本里重新查找：找到 → 重算偏移并重测几何；找不到 → 标 `stale`，保留问答内容、连线改虚线、回答框显示「锚点失效」。
    - 同名片段出现多次时，取**离原偏移最近**的一处（`nearestIndexOf`），不能直接 `indexOf` 取第一个，否则在上方插一行就会把锚点甩到别处。
    - 绝不允许「改代码 = 删掉用户的问题」这种实现。
12. **测试绝不连真实数据库。**
    - 本地 `api/.env` 里有真实 Neon 连接串。所有 `@SpringBootTest` 必须显式清空
      `DATABASE_URL` / `DB_USER` / `DB_PASSWORD`，否则测试里的 insert/delete 直接打到线上库。
    - `MigrationTest` 保留一条护栏用例（断言 `host(inet_server_addr())` 是本机回环），
      任何人把测试指向远端时它会在 10 毫秒内失败。
    - 测试也不用真实 SMTP：用 `@SpyBean` 截获验证码，测试永远不该往真实邮箱投递。
13. **首屏加载必须幂等。** React StrictMode 在开发环境会把 effect 跑两遍，
    `loadWorkspace()` 必须有共享 Promise 去重，否则「新账号首次进入」会并发创建两个示例画布
    （表现为侧栏莫名多出一个同名画布）。任何「加载时顺手创建数据」的逻辑都要按这条检查。
14. **删除 / 切换画布不得提交空状态。** `docs` 里没有当前画布时，`Canvas` 的解构会直接崩。
    删最后一个画布要「先建好新的再一次性替换」，不能在中间 `set({docs: {}})` 留一帧空态；
    `Canvas` 自身也要有 `if (!doc)` 兜底。
15. **部署必须单容器同源，禁止把前后端拆成两个域名。**
    拆开就是跨域：Cookie 要 `SameSite=None`+`Secure`、后端要加 CORS，两处都可能出错，
    而且用户要记两个网址。根目录 `Dockerfile` 三阶段构建（Node 编译前端 → 拷进
    `api/src/main/resources/static/` → Maven 打包），一个域名同时服务 `/` 和 `/api/*`。
    这条不是审美偏好：`/api/*` 走相对路径 + 同源 Cookie 是当前鉴权能成立的前提。
16. **`*.vercel.app` 域名在国内直连不可用；要用 Vercel 就必须接受"只有挂梯子的人能打开"。**
    2026-11 实测：域名解析被污染到 `31.13.x.x`（Facebook 网段），浏览器报
    `ERR_NAME_NOT_RESOLVED`；容器本身构建、启动、连库全部正常，纯粹是域名打不开。
    挂梯子后完全可用，且 Hobby 免费不需要绑卡 —— 这是它相对 Render/Koyeb 的唯一优势。
    走这条路时**必须用根目录的 `Dockerfile.vercel`（单容器全栈），且 Vercel 项目的
    Root Directory 要设为仓库根**；设成 `api` 会退化成"只有接口、没有页面"。
    要让不挂梯子的人也能打开，只能换国内服务器或绑自定义域名。
17. **免费海外容器平台都要绑卡，没有卡就别在这条路上耗。**
    2026-11 实测：Render、Koyeb、Fly.io、Railway 均要求绑卡做身份验证（$1~$29 预授权）；
    Northflank 定价页写 "No card needed"，但其帮助文档写明
    `all users must add a payment method to start creating resources`。
    因此线上默认走**国内轻量服务器**（支付宝付款、`IP:8080` 访问、不需要备案、不休眠），
    方案见 `deploy/DEPLOY-服务器.md`，打包用 `deploy/build-package.sh`。
18. **服务器上不做构建，本地打好包含前端的 jar 再上传。**
    仓库是私有的（`git clone` 要 Token）、国内机器拉 Docker Hub 与 Maven Central 都不稳、
    2G 内存同时跑 npm+mvn 有 OOM 风险——三个失败点合成一个，就是「上传 26MB 的 jar」。
    `deploy/build-package.sh` 负责这条链路，别再在服务器上写「先 npm install 再 mvn」的步骤。
19. **任何跨请求的状态都不能放 JVM 内存。**
    最初的图形码答案存在 Caffeine 里，Vercel 容器闲置 5 分钟就缩容、多实例之间也不共享内存，
    于是「拿到图 → 慢慢输入 → 提交」可能落到另一个实例上，答案是 null，
    用户看到「验证码已过期」——而那张图生成不到一分钟。这类 bug 本地永远复现不了。
    **已改为 `captcha_sessions` 表**（只存 SHA-256、5 分钟 TTL、`delete ... returning` 原子消费）。
    发信频控从一开始就在 `email_codes` 表里按行统计，没有内存计数。
    新增任何「短生命周期状态」先问一句：多实例 / 重启后还成立吗？
20. **AI 问答的提示词必须带行号，且只喂必要上下文。**
    用户对照的是画布行号栏，回答里说「第 5 行」才对得上；整段代码全塞进去，
    模型会泛泛复述一遍。超过 6000 字符只取选中区域上下各 40 行，并注明省略行数。
    回答框宽度只有 320px，所以提示词硬要求「500 字以内、不要整段复述代码」。
21. **回答接口用 `POST` 而不是 `GET`（对设计稿的偏离）。**
    它需要把「用户此刻屏幕上的代码」一起带上去：画布快照是 700ms 防抖保存的，
    刚粘完代码立刻提问时快照里还没有这段代码，模型会拿到空上下文然后瞎编。
    带请求体也让「改完代码重新回答」天然成立。
22. **解密只发生在调模型的那一刻。** API Key 的明文不允许出现在日志、响应、
    异常栈、快照里；`answers` 表只记 `provider/model`，绝不记 Key。
    接口回显只有掩码（`sk-****3f8a`），这条由 `AiSettingsApiTest` 断言守着。
23. **离屏渲染（导出）不能写回 store，也不能栅格化带视口 transform 的那份 DOM。**
    导出走的是「同一套组件 + `FlatContext` 非 null」的第二份实例：`AnswerBox` 不登记 `boxH`、
    `CodeBlock` 不重测锚点、hover 不生效；世界坐标用 `left/top` 偏移代替 `transform`，
    连线层 svg 要撑成整张画布大小（屏幕上是 1×1 + `overflow:visible` 的技巧，
    序列化进 `<foreignObject>` 时会被裁掉）。违反其中任何一条的表现都是「导出的图缺东西
    或位置整体偏移」，而且只在导出时出现。
24. **凡是「渲染时顺手改状态」的副作用，都要能被导出模式关掉。**
    这不是导出专有的洁癖：同一个组件渲染两次就会有两个 `ResizeObserver` 往同一份状态里写，
    谁的尺寸后到就以谁为准，屏幕上会莫名跳一下。

---

## 2. 技术选型（已定，勿随意更换）

### 2.1 前端

| 关注点 | 选择 | 理由 |
|---|---|---|
| 框架 | React 18 + TypeScript + Vite | 生态标准，构建产物纯静态，Vercel 零配置 |
| 画布渲染 | DOM 节点层 + SVG 连线层 + CSS `transform: translate() scale()` | 见硬约束 1 |
| 状态 | Zustand | 轻，无 Provider 嵌套，适合画布这种高频更新 |
| 样式 | 原生 CSS + CSS 变量 | 不引 Tailwind，避免与画布内联样式冲突 |
| 代码高亮 | 自写轻量分词器 `canvas/highlight.ts`（java/cpp/python/js/go/text），输出只切 `<span>` 不改文本 | 文本节点顺序与字符总数必须不变，否则锚点换算失效 |
| 导出图片 | `html-to-image`（DOM → `<foreignObject>` → canvas） | 不引 Canvas 重绘方案：回答框里的 Markdown 换行、内联代码样式手写重绘成本极高 |
| 字体 | 系统等宽栈 `ui-monospace / Menlo / Consolas`，不引 Google Fonts | 国内 Google Fonts 不稳；导出的字面也因此与屏幕一致 |

### 2.2 后端

| 关注点 | 选择 |
|---|---|
| 语言/框架 | Java 21 + Spring Boot 3.3.x，Maven 单模块 |
| 依赖 | `web`, `validation`, `jdbc`（**不用 data-jpa**，理由见下）, `flyway`, `mail`, `spring-security-crypto`（只取 BCrypt，不引整个 Security 过滤器链） |
| 流式转发 | Java 21 虚拟线程 + `java.net.http.HttpClient` + `SseEmitter`（不引 WebFlux） |
| 数据库 | PostgreSQL（Neon 免费版，Vercel Marketplace 一键集成） |
| 迁移 | Flyway，`V1__init.sql` 起步，禁止手工改线上表 |

> **与初版选型的偏差（已执行，记录在此）**
> 1. `data-jpa` → `spring-boot-starter-jdbc`：画布结构整体存 `jsonb` 快照，实体映射收益低；
>    且容器无状态、无二级缓存，JPA 的懒加载与会话边界只会制造坑。改显式 SQL 后，
>    `JdbcTemplate` 足以覆盖全部场景，依赖更少、启动更快。
> 2. `users.email` 不用 `citext` 扩展：改 `text` + `lower(email)` 唯一索引，语义等价，
>    不依赖扩展权限（Neon 换库、将来换托管都不用重新申请扩展）。
> 3. 测试用 `io.zonky.test:embedded-postgres` 拉真实 PostgreSQL。**不要在测试里用 H2**：
>    迁移里有 jsonb、`::jsonb` 转换、部分索引、check 约束，H2 兼容模式跑不通，测出来的通过是假的。
> 4. 图形码用 `SpecCaptcha`（字符图），**不要用 `ArithmeticCaptcha`（算式题）**：
>    它内部靠 Nashorn（`javax.script` 的 JS 引擎）求值算式，而 JDK 15 起 Nashorn 被移除，
>    JDK 21 上 `text()` 直接 NPE —— 接口上线就是 500。这个坑是 `CaptchaServiceTest` 跑出来的。
> 5. JWT 不引 jjwt，手写 HS256（约 150 行）。但必须守住三条：只接受 `alg=HS256`（挡住
>    `alg=none` 与算法混淆）、签名比对用 `MessageDigest.isEqual`（常量时间）、
>    配了密钥但不足 32 字节直接启动失败（不做静默降级）。
> 6. `caffeine` 依赖已移除：图形码答案落库后没有任何进程内缓存，留着只会让人误以为还有
>    「内存态」需要维护。移除依赖比留一个「以后可能用得上」的库更安全。
| 会话 | JWT HS256 放 **httpOnly + Secure + SameSite=Lax Cookie**（同源代理使 Cookie 可用，避免 localStorage 被 XSS 偷） |
| 部署 | `Dockerfile.vercel`，监听 `$PORT` |

### 2.3 外部服务

| 用途 | 方案 | 备注 |
|---|---|---|
| 注册人机校验 | 自研图形码（easy-captcha）+ 频控 | 抽象 `CaptchaProvider`，后续可换阿里云验证码 2.0 |
| 发信 | QQ 邮箱 SMTP，**465 SSL** | 25 端口平台封禁；备用方案为 HTTPS API 发信（Brevo/Resend） |
| AI | 用户自带 Key，**预设只有 DeepSeek**，其余走自定义 base_url | 不内置任何免费额度。不做「六个 Provider 随便选」：没有真实 Key 就无法验证，最后只是一排点了报错的按钮 |

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
│       │   ├── Canvas.tsx      # 视口、平移缩放、装配各层
│       │   ├── CodeBlock.tsx   # 原子代码块（含行号、高亮、编辑）
│       │   ├── SelectionPlus.tsx
│       │   ├── ConnectorLayer.tsx
│       │   ├── AnswerBox.tsx
│       │   ├── anchors.ts      # 字符偏移 ↔ Range 互转
│       │   ├── MarkdownLite.tsx # 回答正文的极简 Markdown（不用 innerHTML）
│       │   ├── flat.ts         # 导出模式上下文（非 null = 离屏导出视图）
│       │   ├── ExportStage.tsx # 导出用的 1:1 无交互视图
│       │   ├── exportPng.ts    # 量高度 → 算外接框 → 栅格化 → 下载
│       │   ├── workspace.ts    # 首屏加载画布、首次示例问答注入
│       │   └── layout.ts       # 避让与自动整理
│       ├── ai/                 # M4：提问→落库→流式回答的编排（ask.ts）
│       ├── ui/                 # 登录页、侧栏、工具栏、AI 设置弹窗
│       ├── api/                # client.ts（统一 fetch/HttpError）+ auth.ts + canvas.ts + ai.ts + qa.ts（SSE 解析）
│       └── store/
│           ├── canvasStore.ts  # 画布状态与全部交互动作
│           ├── persist.ts      # 防抖自动保存、409 冲突处理
│           └── sessionStore.ts
└── api/
    ├── Dockerfile.vercel
    ├── pom.xml
    └── src/main/
        ├── java/com/codeexplain/
        │   ├── auth/           # 图形码（落库）、邮件码、登录
        │   ├── canvas/         # 画布 CRUD + 乐观锁
        │   ├── qa/             # M4：提问、提示词、SSE 流式回答
        │   ├── ai/             # M4：Provider 预设、SSRF 校验、AES-GCM、OpenAI 兼容客户端
        │   └── common/         # 异常、错误码、JWT
        └── resources/
            ├── application.yml
            └── db/migration/V1__init.sql / V2__captcha_sessions_and_client_id.sql
```

---

## 5. 数据模型

**画布 = 一整块快照**（`canvases.data` JSONB），问答的展示态也在里面；
`questions` / `answers` 两张关系表留给 M4 存 AI 调用记录（审计、重答、token 统计），
不参与渲染回读。理由见第 12 节最后几条。

```sql
users(id uuid pk, email text, lower(email) 唯一索引, password_hash, status, created_at, email_verified_at)
email_codes(id uuid, email, code_hash, purpose, expires_at, consumed_at, ip, attempts)
captcha_sessions(id text pk, answer_hash, ip, expires_at, created_at)          -- V2：图形码答案落库
canvases(id uuid, user_id, title, data jsonb, version int default 0, created_at, updated_at, deleted_at)
questions(id uuid, canvas_id, client_id text, block_id, anchor jsonb, text, box_x, box_y, pinned bool, created_at)
answers(id uuid, question_id, content text, status, model, prompt_tokens, completion_tokens, created_at)
user_ai_settings(user_id pk, provider, base_url, model, api_key_cipher, api_key_mask, extra jsonb, updated_at)
```

`questions.client_id`（V2 新增）是前端生成的 `q_xxx`，与 `canvas_id` 组成唯一索引
（`where client_id is not null` 的部分索引）：快照里的问答靠它回指这条调用记录。
**不要**把 `questions.id` 改成 text 来对齐——换主键会让所有外键跟着改，风险远大于收益。

`canvases.data` 结构（M3 实际写入的形态）：

```json
{
  "viewport": { "x": 250, "y": 70, "zoom": 1 },
  "bg": { "pattern": "dot", "tone": "warm" },
  "blocks": [
    {
      "id": "b_sample",
      "x": 80, "y": 60,
      "width": 520,
      "title": "Solution.java",
      "language": "java",
      "content": "class Solution {\n  ...\n}"
    }
  ],
  "questions": [
    {
      "id": "q_lx8f2a",
      "blockId": "b_sample",
      "anchor": { "startOffset": 96, "endOffset": 111, "quote": "while (lo < hi)", "startLine": 4, "startChar": 8, "endLine": 4, "endChar": 23 },
      "anchorPoint": { "x": 400, "y": 150 },
      "text": "这里为什么用 lo < hi？",
      "answer": "...",
      "status": "done",
      "boxX": 820, "boxY": 126, "boxH": 220,
      "side": "right",
      "sideMode": "auto",
      "pinned": false,
      "collapsed": false,
      "model": "mock-model"
    }
  ]
}
```

**故意不落库的字段**：`anchorRects`（逐行像素矩形，几 KB 且能由 `anchor` + 文本重算，
刷新后 `CodeBlock` 会用 `measureAnchor` 重新测量）。快照里出现像素矩形是错误做法。

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

- 粘贴整段代码 → 生成一个块，宽默认 520，代码行高 20px、字号 13px
- 拖拽：`pointerdown` 命中标题栏或内边距 → 进入拖动；命中正文 → 交给浏览器做文本选择
- 拖动时 `user-select: none`，但**不改动文本节点**（改 DOM 会让已选中的 Range 失效）
- 块内滚动超过 520px 时块内纵向滚动；横向上**不换行**，超宽则块内横向滚动

**编辑与删除（`data-node="block"` 上的操作入口）**

- 标题栏右侧两个按钮：`编辑`、`删除`。按钮区 `onPointerDown` 走 `closest('button, input, select, .del-confirm')` 早退，避免点按钮变成拖块
- 编辑态把 `.code-scroll` 替换成 `.edit-area`（`.edit-gutter` + `textarea.code-edit`）+ `.edit-bar`（文件名 / 语言 / 清空 / 取消 / 保存）
- **`.edit-area` 必须显式设高度**：`height = min(520, max(120, 行数*20 + 24))`。只有 textarea 设高度时，flex 容器会被行号栏内容撑高，长代码时整块被拉到几千像素
- 行号栏 `overflow: hidden`，靠 `textarea.onscroll` 同步 `gutter.scrollTop`；编辑态行号栏不需要 sticky（横向滚动发生在 textarea 内部）
- 快捷键：`Tab` 插入 4 空格、`Ctrl/Cmd+Enter` 保存、`Esc` 取消
- 保存前统一 `normalizeCode`：`\r\n → \n`、去首尾空行与行尾空白，避免行号多出一行空白
- 编辑态下**捕获不到选中提问**：`pre` 已被替换，选区属于草稿，没有稳定偏移
- 删除是两段式：点「删除」→ 标题栏变红色确认条（带提问数量）→ 再点「删除」才真正删；取消恢复原状

**行号栏（gutter）**

- 行号是 `<pre>` 的**兄弟节点**，不在 `<pre>` 内——放进去会污染字符偏移，整个锚点体系立刻失效
- 代码区结构：`.code-scroll`（滚动容器，flex）→ `.gutter`（`position: sticky; left: 0`）+ `pre.code`
- 行号与代码行必须共用 `line-height: 20px` 与 `padding-top: 12px`，否则逐行错位
- `user-select: none`，保证复制代码时不会把行号带进去

**语法着色**

- `canvas/highlight.ts` 是手写 tokenizer，不引 highlight.js/Prism——它们会输出 HTML，而这里只需要带 class 的文本片段
- 铁律：**token 拼起来必须与原文逐字符相等**，覆盖率为 100%，换行原样保留在文本里
- 用整段 tokenize + 行内 `<span>`，**禁止按行拆成 `<div>`**：Range 跨块元素时 `toString()` 不会补换行，偏移会整体错位
- 分色：`kw` 关键字 / `type` 类型与类名 / `fn` 函数名 / `str` 字符串 / `num` 数字 / `com` 注释 / `anno` Java 注解
- 支持 java / cpp / python / javascript / go；`text` 不做着色。语言名大小写、`js`/`py`/`c++` 等别名走 `ALIAS` 归一

### 6.3 选中 → 加号 → 提问

1. 鼠标 `mouseup` 时读 `window.getSelection()`
2. 校验：非空、完全落在同一个块内、不是跨块选择
3. 取 `range.getClientRects()` 最后一行的右端 + 10px、垂直居中，放圆形 `+` 按钮（固定屏幕尺寸，不随缩放变大）
4. 点击 `+` → 弹输入框，顶部含「回答位置」三选一：自动 / 左侧 / 右侧
5. 提交后：算锚点 → 建回答框（loading 态）→ 开流式回答；若回答框落在可视区外，平移画布把它拉回来

### 6.4 高亮还原（关键实现）

锚点 → 高亮不用 `mark` 标签包裹文本，而是**在独立的 overlay 层画半透明矩形**：

1. 用 TreeWalker 按字符偏移定位到 Text 节点的 `(node, offset)`——**不能用 `pre.firstChild`**，
   有语法高亮之后 `<pre>` 里是一堆 span，单文本节点假设不成立
2. 构造 `Range`，`getClientRects()` 拿到每行矩形
3. **同一行的多个片段必须合并**：Range 是按行片段返回矩形的，一段选区被 span 切开时同一行会返回多个
   重叠矩形，半透明底色叠加会画出深浅斑块。合并规则：top 差 < 1.5px 视为同一行，取并集
4. 只给最上面那个矩形加左侧竖线（`.hl-start`），跨行选区不会每行都插一根
5. 矩形换算成块内坐标，画在 overlay 上，`pointer-events: none`

好处：DOM 文本结构永远不被修改，锚点不会因高亮而偏移；选中态和持久高亮可以共存。

### 6.5 连线

- 起点：代码块**朝向回答框那条边的中点**——右侧框取 `block.x + block.width`，左侧框取 `block.x`
- 终点：回答框朝向代码块的边——右侧框取 `boxX`，左侧框取 `boxX + BOX_WIDTH`
- 三次贝塞尔，控制点水平外推 `dx = max(40, distance * 0.4)`，方向跟随所在侧（左列向负方向外推）
- 箭头落在代码块边界上，`marker-end` 在起点；锚点投影到块边界而非选区内部，避免被 z-index 压住
- 问题标签：先算出「块边界 ↔ 框边界」的中线 x，再在曲线上取最接近该 x 的点（不固定 t=0.5），
  最长 12 字截断，标签逆缩放 `scale(1/zoom)` 保持可读
- 层级：连线层 `z-index: 10` < 代码块/回答框 `z-index: 20` → **天然被块遮住，不会盖住代码**

### 6.6 双侧布局与避让

- 回答框按 `(blockId, side)` 分组，每侧各自一列：
  - 右列 `x = block.x + block.width + COL_OFFSET`
  - 左列 `x = block.x - COL_OFFSET - BOX_WIDTH`
- 初始 `y`：锚点中心 y − 24
- 避让：同列按锚点 y 排序后贪心下推，`y = max(desiredY, prevBottom + 16)`
- `pinned = true` 的框不参与下推，其他框绕开它
- 拖动回答框跨过代码块中线 → 自动改 `side`，连线换到另一侧，避免箭头横穿代码

**自动分侧的判定（`chooseSide`）**

1. 先算两侧整列能否落在可视区内（左边界要让开 216px 侧栏）
2. 两侧都放得下 → 比较锚点距左右可视边界的空间，放空间大的一侧（这才是「按选中位置判断」）
3. 只有一侧放得下 → 放那侧；都放不下 → 取空间大的一侧
4. 手动指定的一侧若落在可视区外，`ensureVisible` 最小幅度平移画布把它拉回来（自动模式不会触发平移）

### 6.7 同一片段多问题

- 2~3 个：共用同一锚点，连线在起点处按 `±12px` 扇形错开，避免完全重合
- ≥4 个：折叠为堆叠组，只画 1 条线 + 一枚 `还有 N 个` 的胶囊；点击展开扇形
- 连线密度抑制：`opacity = clamp(1 - 0.12 * (n - 1), 0.35, 1)`
- hover 单条线时其余线降到 0.12，代码块高亮对应片段

### 6.8 一键整理

- 触发：工具栏「整理」按钮
- 动作：按 `(代码块, 侧)` 分组 → 重算列 x → 按锚点 y 排序 → 贪心避让 → 仅跳过 `pinned` 框
- 支持「撤销上一次整理」（栈深度 20）—— 尚未实现

### 6.9 回答框

- 可拖动（只动框 + 连线 + 标签，代码绝不动）；拖动结束写回 `box_x/box_y`
- 标题栏按钮：换边（←/→）、锁定、折叠、删除；换边后若落到可视区外同样走 `ensureVisible`
- 底部信息栏显示当前侧、模型、锚点行:列
- 内容支持 Markdown 渲染（只渲染 `code / bold / list / inline code`，不用 `dangerouslySetInnerHTML` 直接吞全文）
- 状态：`loading / streaming / done / error / no-key`

### 6.10 画布背景

- 数据在 `CanvasDoc.bg = { pattern: 'dot' | 'grid' | 'line' | 'blank', tone: 'warm' | 'white' | 'cool' | 'dark' }`，**按画布独立保存**
- 图案用 `background-image` 生成：点阵 `radial-gradient`、方格双 `linear-gradient`、横线 `repeating-linear-gradient`
- 对齐规则：`background-size = 24 * zoom`，`background-position = viewport.x/y`——底纹与内容同步平移缩放，没有视差
- 切换走 `setBg(patch)` 做浅合并，**不要传整个 bg 对象**：同一 tick 连点两次会用旧快照覆盖对方

---

## 7. API 契约

以下为**已实现**的部分（M2 + M3），与设计稿的差异已在备注里说明。

```
GET    /api/auth/captcha                    → {id, image}          // image 为 data:image/png;base64
POST   /api/auth/email-code                 {email, captchaId, captchaCode} → {sent, devMode, cooldownSeconds}
GET    /api/auth/policy                     → {emailCooldownSeconds, codeTtlMinutes, maxCodeAttempts, maxPerIpDaily, minPasswordLength}
POST   /api/auth/register                   {email, code, password, displayName} → Set-Cookie + {id, email, ...}
POST   /api/auth/login                      {email, password} → Set-Cookie + 同上
POST   /api/auth/logout                     → 204 + 清 Cookie
GET    /api/auth/me                         → {id, email, displayName, emailVerified, createdAt}

GET    /api/canvases                        → [{id, title, version, updatedAt, blockCount, questionCount}]
POST   /api/canvases                        {title?, data?} → 201 {id, title, data, version}
GET    /api/canvases/{id}                   → {id, title, data, version, createdAt, updatedAt}
PUT    /api/canvases/{id}                   {title, data, baseVersion} → 200 新版本；版本不一致 409
DELETE /api/canvases/{id}                   → 204（软删，deleted_at 非空）
```

M4 已实现（与设计稿的偏差见下方）：

```
GET    /api/me/ai-settings                  → {provider, baseUrl, model, apiKeyMask, hasKey, keyEncryptionReady, presets}
PUT    /api/me/ai-settings                  {provider?, baseUrl?, model?, apiKey?, clearKey?} → 同上（apiKey 留空 = 不改）
POST   /api/me/ai-settings/test             → {ok, latencyMs, model, error?}（失败也 200，文案可读）
POST   /api/canvases/{id}/questions         {clientId, blockId, anchor, text, code?, language?, title?} → {questionId, clientId}
POST   /api/questions/{id}/answer           {code?, language?, title?} → text/event-stream
```

SSE 事件：`start{answerId,model}` → `delta{text}`* → `done{model,promptTokens,completionTokens}`；
失败推 `error{code,message}`。**`start` 之前的所有失败都是普通 JSON 4xx**（没配 Key → 400 `AI_NOT_CONFIGURED`），
前端必须先看响应头再决定怎么读。

**设计稿与实现的偏差**：

| 设计稿 | 实现 | 原因 |
|---|---|---|
| `GET /api/me` | `GET /api/auth/me` | 与其余认证接口同一前缀，拦截器白名单只需维护一处 |
| `PATCH /api/canvases/{id}` 只改标题 | 并入 `PUT` 全量 | 标题与快照一起提交通道更少；前端本来就要保存快照，没必要两条路径 |
| `PUT /api/canvases/{id}/data` | `PUT /api/canvases/{id}` | 画布没有「除 data 之外」的可写字段，多一层路径无收益 |
| `GET /api/questions/{id}/answer`（无请求体） | `POST /api/questions/{id}/answer`（带当前 code） | 快照有 700ms 防抖，刚粘完代码立刻提问时快照里还没有它；带请求体也让「改完重答」天然成立 |

**错误码**：统一错误体 `{code, message, field?}`，前端按 `code` 分支。

| code | HTTP | 含义 |
|---|---|---|
| `CANVAS_NOT_FOUND` | 404 | 画布不存在 / 已删 / 不属于当前账号（三者统一，避免探测别人的 id） |
| `CANVAS_VERSION_CONFLICT` | 409 | `baseVersion` 落后，别人已保存过 |
| `CANVAS_LIMIT_REACHED` | 409 | 单账号画布数达上限（默认 50，`CANVAS_MAX_PER_USER` 可调） |
| `CANVAS_TOO_LARGE` | 413 | 单次快照超过 2MB（`CANVAS_MAX_DATA_BYTES` 可调） |
| `QUESTION_NOT_FOUND` | 404 | 提问不存在 / 不属于当前账号 |
| `BASE_URL_FORBIDDEN` | 400 | 模型地址不是公网 https，或解析到了内网 / 本机 |
| `AI_NOT_CONFIGURED` | 400 | 还没保存 API Key，或密文解不开（主密钥换过） |
| `AI_UPSTREAM_ERROR` | 502 | 模型服务返回错误（401/402/404 已翻译成可照做的中文） |
| `AI_RATE_LIMITED` | 429 | 模型服务限流 |

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
| **M0.5 可行性冒烟** ✅ | 最小 Spring Boot 容器 + `Dockerfile.vercel` 推 Vercel | 公网可访问 `/actuator/health`；确认 Hobby 允许容器；记录冷启动耗时与 SSE 是否可跑满 60s |
| **M0 交互原型** | 纯前端单页，无后端，回答用假数据 | 粘贴代码成块、整块拖动、选中出加号、连线带问题标签、回答框避让、一键整理，全部可交互 |
| **M1 后端骨架** ✅ | Spring Boot + Flyway（V1: 6 张表）+ Neon 连接串兼容 | 内嵌 PG 跑真实迁移通过；`/api/db/ping` 返回 schema 版本与表清单；无库时可用 `DB_OPTIONAL=true` 启动 |
| **M2 注册登录** ✅ | 图形码 + 6 位邮件码 + 频控 + BCrypt + httpOnly Cookie 会话 | 真实 Neon 上完成注册/登录/刷新/登出；真实 QQ SMTP 发信成功；11 个认证用例覆盖边界 |
| **M3 画布持久化** ✅ | 多画布 CRUD、jsonb 快照、`version` 乐观锁、前端防抖自动保存 | 刷新不丢内容；两标签页同改触发 409 并有「重新加载 / 以我为准」两个出口 |
| **M4 AI 问答** ✅ | Provider 抽象、SSRF 校验、加密存储、SSE | DeepSeek 单预设 + 自定义 base_url；Key 掩码回显；内网地址被拒；流式增量落库；`AI_NOT_CONFIGURED` 自动弹出设置 |
| **M5 上线** ✅ | GitHub push → Vercel **单容器**（非双项目） | 线上 <https://code-explain-nine.vercel.app> 走通注册→登录→建画布→自动保存 |
| **M6 打磨** | 代码高亮、导出 PNG ✅、性能虚拟化 | 导出：内容完整、四边留白一致、2× 分辨率；性能：200 个节点下拖动不掉帧（**未做**，仍全量渲染） |

**顺序纪律**：M0.5 不过，不写业务代码。M0 手感没被确认，不做 M1。

---

## 10. 当前状态与待办

- [x] 需求拆解、选型定稿
- [x] 部署策略可行性判断（GitHub + Vercel 通）
- [x] **M0 画布交互原型**（`web/`，模拟回答数据，已用无头浏览器逐项验证）
- [x] **M0.5 冒烟包**（`api/`，`/api/ping` + `/api/dev/stream`）
- [x] 画布增强：背景切换、回答框左右分侧、行号、语法高亮
- [x] 代码块编辑 / 删除 + 编辑后锚点重定位
- [x] **M1 后端骨架**：Flyway V1（users / email_codes / canvases / questions / answers / user_ai_settings）、
      数据库连接串归一化、`/api/db/ping` 自检；12 个测试（含真实 PG 迁移）全过
- [x] **代码已推送到 GitHub**：`yangzhudaheng/code-explain` 私有仓库，42 个文件，
      远端 HEAD 与本地一致（建仓时的空历史已用 `--allow-unrelated-histories` 合并，未覆盖任何内容）
- [x] **M2 注册登录**：图形码（`SpecCaptcha`）+ 邮件码（BCrypt 存哈希）+ 频控（同邮箱 60s / 同 IP 每日 10 次）
      + BCrypt 密码 + HS256 JWT 存 httpOnly Cookie；前端登录/注册页与会话守卫
- [x] **M3 画布持久化**：`/api/canvases` 五个接口 + jsonb 快照 + `version` 乐观锁 + 软删；
      前端防抖 700ms 自动保存、保存状态条、跨标签页 409 与「重新加载 / 以我为准」；
      **后端 46 个测试全过**（新增 11 个画布用例），真实 Neon 上 API 与浏览器端到端均通过
- [x] **M0.5 部署验证 + M5 上线**：Vercel 容器（Hobby 免费、不需要绑卡）已跑通，
      线上 <https://code-explain-nine.vercel.app>，**单容器同源**（根目录 `Dockerfile.vercel`，
      前端打进 jar 的 `static/`）。验证记录：
      `/` → 前端 HTML、`/api/ping` → 200、`/api/db/ping` → Neon 连通、`/api/auth/captcha` →
      200 且 PNG 有字（Alpine 字体装对了）、`/api/canvases` 未登录 → 401、真实 QQ 邮箱注册登录成功
- [ ] **区域优化（有体感，值得做）**：Vercel 函数默认在华盛顿、Neon 在新加坡，
      `/api/db/ping` 实测 1601ms。项目 `Settings → Functions → Function Region` 改 `sin1`
      后重新部署，预期降到 50ms 级（改区域会触发重新部署，注意 `Settings → Functions` 里
      **Fluid compute 必须开着**，否则容器函数直接拒绝部署）
- [ ] 用户重置 QQ SMTP 授权码（旧码已泄露）
- [ ] 域名（可选）：不绑域名则只有挂代理的人能打开 `*.vercel.app`
- [x] **M4 前置一**：V2 迁移给 `questions` 加 `client_id text` + (canvas_id, client_id) 部分唯一索引，
      快照里的 `q_xxx` 与 `questions` 表从此能对上
- [x] **M4 前置二**：图形码答案从 Caffeine 迁到 `captcha_sessions` 表
      （SHA-256 + 5 分钟 TTL + `delete ... returning` 原子消费），多实例/重启不再随机报过期
- [x] **M4 本体**：`ai/`（SecretBox、BaseUrlGuard、ChatClient、AiSettings*）+ `qa/`
      （QuestionRepository、AnswerRepository、ExplainPrompt、QaService、QaController）+
      前端 `ui/AiSettings.tsx`、`ai/ask.ts`、`api/{ai,qa}.ts`；新增 33 个测试，后端共 85 个全过
- [ ] 待确认（有真实 Key 才能验）：DeepSeek 的 `stream_options.include_usage` 是否回 token 用量；
      `deepseek-reasoner` 的 `reasoning_content` 目前不展示（只取 `content`），要不要显示「思考中」
- [ ] 回答框目前是纯文本渲染 Markdown 源文（`**`、`` ` `` 可见）。
      要做得更好需要一个「只处理 code/bold/list/inline-code」的小渲染器，**禁止 dangerouslySetInnerHTML**
- [x] **M6 导出 PNG**：`canvas/flat.ts`（导出模式上下文）+ `canvas/ExportStage.tsx`（离屏视图）+
      `canvas/exportPng.ts`（量高度 → 算内容外接框 → 离屏渲染 → `html-to-image` 栅格化 → 下载），
      工具栏加「导出 PNG」按钮。实测：1560×464 逻辑尺寸、2× 输出 3120×928，四边留白 49~53px，
      高亮带间距 80px = 2 行 × 20px，与屏幕锚点完全一致
- [ ] 性能虚拟化：视口外的回答框仍全量渲染，节点多了拖动会重排全部 DOM（M6 未做部分）

### M4 已验证的行为（回归基线）

| 行为 | 期望 |
|---|---|
| 没配 Key 就提问 | 回答框显示「还没配置模型 API Key」+ 自动弹出「AI 设置」；后端回 400 `AI_NOT_CONFIGURED` |
| 填入 Key 保存 | 接口只回 `sk-****xxxx` 掩码；库里 `api_key_cipher` 是密文，`psql` 里看不到明文 |
| 只改模型名不填 Key | 原 Key 保留，`hasKey` 仍为 true |
| 点「测试连接」 | 成功显示往返毫秒数；Key 错/余额不足显示可照做的中文原因，不是 500 |
| base-url 填内网 | 400 `BASE_URL_FORBIDDEN`，且不落库 |
| 提问 → 流式回答 | 回答框逐字出现；结束后 `answers` 表 status=done 且内容与框内一致 |
| 回答中切画布 | 上游请求被 abort，不再产生增量（不白花 token） |
| 删掉正在生成的问答 | 先断流再删，不留孤儿答案 |
| 回答失败后点 ↻ | 用当前代码重新生成一条新 `answers` 记录，旧记录保留 |
| 同一问答重复提交 | 按 `clientId` 幂等，只有一条 `questions` 记录 |

### M3 已验证的行为（回归基线）

| 行为 | 期望 |
|---|---|
| 首次登录（无画布） | 自动建一个「二分查找 · 边界问题」示例画布，并注入 2 条示例问答 |
| 刷新页面 | 代码块、回答框、回答文本、缩放/平移位置全部原样回来 |
| 拖动代码块 / 回答框后刷新 | 位置保持；已回答的问答不丢 |
| 再开一个标签页 | 加载同一画布；两个标签页各自有独立的 `version` |
| 两标签页都改 | 后保存的一方出现「已在别处修改」+「重新加载 / 以我为准」，且**不会静默覆盖** |
| 点「重新加载」 | 本地未保存改动丢弃，内容回到服务端最新版 |
| 点「以我为准」 | 先取最新版本号再写，覆盖对方改动成功 |
| 新建画布 | 立刻出现在列表首位并切换过去，刷新后仍停在它（localStorage 记 active） |
| 双击画布标题 | 就地重命名，Enter 提交、Esc 取消，随后自动保存 |
| 删除画布 | hover 出现 × → 二次确认 → 从列表消失；删最后一个会自动补一个空画布 |
| 断网或服务端 500 | 保存状态变「保存失败 + 重试」，本地内容不丢 |
| 登出再登录 | 上一个账号的画布不残留、不闪现 |

### M0 已验证的行为（回归基线，改动后需复测）

| 行为 | 期望 |
|---|---|
| 拖代码块标题栏 | 整块移动，字符不可单独拖动 |
| 拖选代码片段 | 选区末尾同行出现加号，不压字 |
| 点加号提问 | 新建回答框，宽度 320，落在所选一侧的回答列 |
| 拖回答框 | 框移动，连线与问题标签跟随，代码块坐标不变 |
| 拖框后 | 其余未锁定框自动下推，不重叠 |
| 点「整理」 | 解除锁定并回到按锚点 y 排序的列布局 |
| 缩放 | Ctrl+滚轮以指针为锚点，标签逆缩放保持可读 |
| 切画布 | 代码块与问答各自独立 |
| 行号 | 与代码逐行对齐，横向滚动时 sticky 不动，不被复制带走 |
| 语法着色 | 关键字/类型/函数/字符串/数字/注释分色，`pre.textContent` 与原文长度、内容完全一致 |
| 跨行选区 | 锚点行:列正确，高亮逐行均匀无缝、无叠深 |
| 自动分侧 | 代码块在屏幕右半时判为左侧；两侧都放得下时按选中位置就近 |
| 手动换边 | 框换到另一侧并保持纵向位置，连线换到该侧边界 |
| 背景切换 | 图案与底色独立生效，平移缩放时底纹与内容同步 |
| 编辑代码并保存 | 行号数量等于新行数；已有问答的锚点随文本重定位（L4→L5），高亮与箭头同步下移一行 |
| 编辑时 Esc / 取消 | 内容不变，不产生新提交 |
| 清空并保存 | 显示「空代码块」占位，原有问答保留但标记「锚点失效」，连线变虚线 |
| 删除代码块 | 二次确认后块、提问、回答框、连线一并消失 |
| 代码区滚轮 | 滚动代码/回答内容，画布不平移；空白处滚轮仍平移画布 |

当前列间距 `COL_OFFSET = 140`，回答框宽度 `BOX_WIDTH = 320`，避让间距 `BOX_GAP = 16`，
侧栏宽度 `SIDEBAR_W = 216`（`layout.ts` 里必须与 CSS 同步）。

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
- **`canvases.data` 就是画布的全部渲染数据**（blocks + questions 的展示态 + viewport + bg），
  一次 PUT 整体覆盖，这是刻意的：画布是「整块快照」语义，拆表会带来部分写失败与写放大。
  但不要因此把**问答的调用记录**也塞进 jsonb——`questions` / `answers` 两张表在 M4 用来存
  AI 调用审计、重答历史与 token 统计，它们不参与渲染，也不做渲染时的回读。
- 前端保存是「引用相等」判脏（`synced` Map 存 doc 引用），不要改成深比较或 JSON 字符串比较，
  拖动代码块时每秒几十次 setState，深比较会直接卡住主线程
- 不确定的交互细节，**先做出来给用户点一下**，比写三页设计文档有用
