# ============================================================
# 单容器全栈镜像：前端静态文件 + Spring Boot API
#
# 为什么合成一个容器而不是拆成两个服务：
# 拆成两个服务就是两个域名，浏览器视角变成跨域 —— Cookie 要改 SameSite=None、
# 后端要加 CORS，两处都容易出错，还多一个要记的网址。
# 合成一个容器后前后端天然同源，这些坑都不存在。
#
# 当前用于 Render（onrender.com 在国内未被污染）；
# 同一个镜像也可以直接跑在 Koyeb / Fly.io / 自己的服务器上。
# ============================================================

# ---------- 阶段 1：构建前端 ----------
FROM node:20-alpine AS web
WORKDIR /web
# 先只拷 lock 文件：依赖没变时这层走缓存，改业务代码不用重装依赖
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

# ---------- 阶段 2：构建后端，并把前端产物塞进 static ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY api/pom.xml .
RUN mvn -q -B -DskipTests dependency:go-offline
COPY api/src ./src
# 顺序不能反：先拷后端源码，再把前端产物覆盖进 static，
# 否则 mvn 打不进静态文件，访问 / 会 404
COPY --from=web /web/dist/ ./src/main/resources/static/
RUN mvn -q -B -DskipTests package

# ---------- 阶段 3：运行 ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 图形验证码用 Java2D 画字，Alpine 默认不带任何字体，
# 缺字体会抛 "Fontconfig head is null" 或画出空白图 —— 必须装字体
RUN apk add --no-cache fontconfig ttf-dejavu \
    && (fc-cache -f > /dev/null 2>&1 || true)

COPY --from=build /src/target/*.jar app.jar

# Render 免费实例只有 512MB 内存，堆不能给大，否则容器直接被 OOM kill：
# 堆 280M + Metaspace 128M ≈ 408M，余下留给线程栈、code cache、直接内存。
# SerialGC + TieredStopAtLevel=1 是为了缩短冷启动 —— 免费实例休眠后每次都要重新拉起。
ENV JAVA_OPTS="-Xmx280m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Dspring.main.lazy-initialization=true" \
    JAVA_TOOL_OPTIONS="-Djava.awt.headless=true"

EXPOSE 8080

# 平台会注入 PORT（Render/Fly/Koyeb 都遵守这个约定），没注入时回落到 8080
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar --server.port=${PORT:-8080}"]
