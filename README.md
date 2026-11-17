# code-explain

把算法题解贴到一张无限画布上，选中任意代码片段提问，AI 的回答以可拖动的方框回挂到该片段——用带问题标签的箭头连过去。

> 详细的技术约束、交互规格与里程碑见 [AGENT.md](./AGENT.md)。改代码前先读它。

## 当前进度

- **M0 画布交互原型**：已完成（回答为模拟数据，无后端）
- **M0.5 容器冒烟包**：已完成（待部署到 Vercel 验证 Hobby 是否允许容器）
- M1~M6：未开始

## 目录

```
web/    React + TypeScript + Vite 前端（画布本体）
api/    Spring Boot 3 / Java 21 后端（当前只有冒烟端点）
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
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | Neon Postgres 连接 |
| `JWT_SECRET` | 会话签名，≥32 字节随机串 |
| `APP_ENC_KEY` | 用户模型 API Key 的加密主密钥（AES-GCM，Base64） |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USER` / `SMTP_PASS` / `SMTP_FROM` | QQ 邮箱 465 SSL |
| `APP_BASE_URL` | 站点对外地址，用于邮件链接 |
