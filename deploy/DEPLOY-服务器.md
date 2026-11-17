# 部署到国内轻量服务器（不需要信用卡）

> 适用场景：只想要一个**国内能直接打开**的网址，没有双币信用卡，不想备案。
> 代价：一台轻量服务器（新用户首年通常几十元），用支付宝/微信付，跟信用卡无关。

## 为什么是这个方案

| 方案 | 为什么不选 |
|---|---|
| Render / Koyeb / Fly / Railway | 免费实例全部要求绑卡验证身份（$1~$29 预授权） |
| Northflank | 定价页写 "No card needed"，但帮助文档写明所有用户创建资源前必须添加支付方式 |
| Zeabur 免费版 | 免费版只能管「你自己买的服务器」，不能用它的云算力 |
| Vercel | 容器能跑，但 `*.vercel.app` 在国内 DNS 被污染，打不开 |
| 腾讯云 CloudBase 默认域名 | 2025-10 之后新建环境的默认域名会插安全提示中间页，生产还要求绑已备案域名 |

轻量服务器用 IP + 端口访问，**不需要备案**（备案只在把域名解析到境内服务器时才需要），国内访问也是最快的，没有休眠和冷启动。

## 一、买机器

- 产品：**腾讯云轻量应用服务器**（阿里云轻量同样可以）
- 配置：**2 核 2G**、40G 系统盘，带宽 3M 起（3M 够个人用）
- 镜像：**Ubuntu 24.04 LTS**（关键：24.04 自带 `openjdk-21`，apt 一条命令就能装；22.04 的官方源里没有 JDK 21）
- 地域：选离你近的（广州/上海/北京），不要选香港/海外，那样就没意义了

> 如果镜像列表里找不到 24.04，先选 22.04 并告诉我，我给你换一套装 JDK 21 的命令。

## 二、放行端口

轻量服务器默认只放行 22 / 80 / 443。控制台 → 服务器详情 → **防火墙** → 添加规则：

- 应用类型：自定义
- 协议：**TCP**
- 端口：**8080**
- 来源：`0.0.0.0/0`

## 三、上传部署包

本机项目根目录下已经生成好：`dist-deploy/code-explain-deploy.tar.gz`（约 26MB，里面是 jar + 配置 + 服务单元）。

上传方式（任选其一）：

- 轻量控制台的 **OrcaTerm 网页终端** → 右上角文件面板 → 上传到 `/root/`
- 或用 WinSCP / FileZilla，SFTP 登录，传到 `/root/`

> 包里的 `app.env` 已经填好了 Neon 连接串、JWT 密钥、QQ 邮箱授权码，只有 `APP_BASE_URL` 需要你改成自己的 IP。

## 四、四条命令装好

登录服务器（网页终端即可），依次执行：

```bash
# 1. 装 Java 21（仓库里没有前端代码，服务器不需要 Node/Maven/Docker）
sudo apt update && sudo apt install -y openjdk-21-jre-headless
java -version          # 必须看到 openjdk 21.x，不是 17

# 2. 建专用低权限账号和目录
sudo useradd --system --no-create-home --shell /usr/sbin/nologin codeexplain || true
sudo mkdir -p /opt/code-explain

# 3. 解压
sudo tar -xzf ~/code-explain-deploy.tar.gz -C /opt/code-explain
sudo chown root:codeexplain /opt/code-explain/code-explain.jar
sudo chmod 600 /opt/code-explain/app.env
sudo chmod 640 /opt/code-explain/code-explain.jar

# 4. 填上你的公网 IP，然后注册成开机自启的常驻服务
sudo sed -i "s|^APP_BASE_URL=.*|APP_BASE_URL=http://把你的公网IP:8080|" /opt/code-explain/app.env
sudo cp /opt/code-explain/code-explain.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now code-explain
```

## 五、验证

```bash
# 服务器内部先自测
curl -s http://127.0.0.1:8080/api/ping
curl -s http://127.0.0.1:8080/api/db/ping

systemctl status code-explain --no-pager
sudo journalctl -u code-explain -n 50 --no-pager   # 看启动日志
```

然后在浏览器打开 `http://你的公网IP:8080/`：

| 期望 | 说明 |
|---|---|
| 出现登录页 | 前端打通 |
| `/api/ping` 返回 `{"ok":true,...}` | 后端活着 |
| `/api/db/ping` 返回 Neon 版本 + 7 张表 | 数据库通了（跨洋约 60~190ms，正常） |

## 六、出问题怎么查

| 现象 | 原因 | 处理 |
|---|---|---|
| 浏览器一直转圈 / 超时 | 防火墙没放行 8080 | 回第二节加规则 |
| `Connection refused` | 服务没起来 | `journalctl -u code-explain -n 50` 看报错 |
| 日志里 `JWT_SECRET` 相关报错 | 密钥少于 32 字节 | `openssl rand -base64 48` 重新生成后填进 app.env，再 `systemctl restart code-explain` |
| 能登录但刷新就掉线 | `COOKIE_SECURE=true` 但走的是 http | 改回 `false` 重启 |
| 日志 `Connection to ... failed` | Neon 连接串被截断 | 检查 app.env 里 `DATABASE_URL` 整行完整、含 `?sslmode=require` |
| 启动报 Flyway 校验失败 | 数据库里有旧版本迁移 | 一般不会；把日志发我 |

## 七、以后怎么更新

1. 本机改完代码，跑 `bash deploy/build-package.sh` 重新打包
2. 上传新的 `code-explain-deploy.tar.gz`
3. **只更新 jar，别覆盖 app.env**（否则你填的 IP 会被冲掉）：

```bash
# 注意最后那个 code-explain.jar：只解这一个文件，app.env 保持不动
sudo tar -xzf ~/code-explain-deploy.tar.gz -C /opt/code-explain code-explain.jar
sudo chown root:codeexplain /opt/code-explain/code-explain.jar
sudo systemctl restart code-explain
```

## 八、必须知道的两个限制

**1. 现在是 http 明文。** 没有域名就没有免费证书，登录密码在网络上是明文传输的。
- 自己用、在可信网络里，风险可接受
- 如果要给别用，或者在意安全：买个便宜域名（十几块/年）+ 解析到这台服务器 + Let's Encrypt 免费证书，我帮你配 Nginx + HTTPS；**这时候才需要备案**
- 换 HTTPS 后记得把 `COOKIE_SECURE` 改回 `true`

**2. 单机单进程。** 机器挂了就没了，没有自动扩容/异地容灾。个人工具够用，不做高可用。

## 九、可选：以后想换回 Docker 部署

根目录的 `Dockerfile` 是三阶段构建（前端 + 后端 + 运行），在任何支持 Docker 的机器上都能跑：

```bash
git clone <仓库> && cd code-explain
docker build -t code-explain .
docker run -d --name code-explain --restart unless-stopped -p 8080:8080 --env-file deploy/app.env code-explain
```

但国内机器拉 Docker Hub 要走镜像加速（腾讯云：`/etc/docker/daemon.json` 里配 `https://mirror.ccs.tencentyun.com`），并且构建要下 Maven/npm 依赖，比现在的「上传 jar」慢且更容易失败，所以默认不推荐。
