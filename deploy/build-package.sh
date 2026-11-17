#!/usr/bin/env bash
# 打一个「服务器上直接能跑」的部署包：前端已内置进 jar，服务器不需要 Node/Maven/Docker。
#
# 用法（在本机项目根目录）：bash deploy/build-package.sh
# 产物：dist-deploy/code-explain-deploy.tar.gz（含 jar + app.env + systemd 单元）
#
# 为什么不在服务器上构建：
#   1. 仓库是私有的，服务器拉代码要配 Token，多一个出错点
#   2. 服务器拉 Docker Hub / Maven Central 都不稳，构建容易卡死
#   3. 2G 内存的机器同时跑 npm + mvn，有 OOM 风险
# 本地打好包，服务器只装 JRE，失败点从 5 个降到 1 个。

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/dist-deploy"

# 本机 JDK/Maven 不在 PATH 里时，用上次验证过的位置兜底
if ! command -v mvn >/dev/null 2>&1; then
  export JAVA_HOME="${JAVA_HOME:-/c/tmp/tools/jdk/jdk-21.0.13+9}"
  export PATH="$JAVA_HOME/bin:/c/tmp/tools/mvn/apache-maven-3.9.9/bin:$PATH"
fi
command -v mvn >/dev/null 2>&1 || { echo "找不到 mvn，请先设置 JAVA_HOME 与 PATH"; exit 1; }

echo "== 1/4 构建前端 =="
cd "$ROOT/web"
[ -d node_modules ] || npm ci --no-audit --no-fund
npm run build

echo "== 2/4 把前端产物塞进后端 static =="
rm -rf "$ROOT/api/src/main/resources/static"
mkdir -p "$ROOT/api/src/main/resources/static"
cp -r "$ROOT/web/dist/." "$ROOT/api/src/main/resources/static/"

echo "== 3/4 打包后端 jar（含静态资源）=="
cd "$ROOT/api"
mvn -q -B -DskipTests package

echo "== 4/4 组装部署包 =="
mkdir -p "$OUT"
rm -f "$OUT"/*.tar.gz
cp "$ROOT/api/target/code-explain-api-0.1.0.jar" "$OUT/code-explain.jar"
cp "$ROOT/deploy/code-explain.service" "$OUT/code-explain.service"

# 从本地 api/.env 抽值生成服务器配置：密钥不手抄，避免抄错一个字符排查半天
{
  echo "# 由 deploy/build-package.sh 从 api/.env 生成；本地产物，已被 gitignore"
  echo "# 上线前只需确认最后两行（APP_BASE_URL / COOKIE_SECURE）"
  for k in DATABASE_URL DB_POOL_SIZE JWT_SECRET APP_ENC_KEY SMTP_HOST SMTP_PORT SMTP_USER SMTP_PASS SMTP_FROM; do
    grep -E "^${k}=" "$ROOT/api/.env" || true
  done
  echo "APP_BASE_URL=${SERVER_URL:-http://换成你的服务器IP:8080}"
  echo "COOKIE_SECURE=false"
  echo "CAPTCHA_DEBUG=false"
} > "$OUT/app.env"

# 权限跟着 jar 一起进包，上传解压后就是 600
chmod 600 "$OUT/app.env" 2>/dev/null || true

tar -czf "$OUT/code-explain-deploy.tar.gz" -C "$OUT" code-explain.jar app.env code-explain.service

echo
echo "完成：$OUT/code-explain-deploy.tar.gz  ($(du -h "$OUT/code-explain-deploy.tar.gz" | cut -f1))"
echo "把这个包上传到服务器，然后按 deploy/DEPLOY-服务器.md 里的命令执行。"
