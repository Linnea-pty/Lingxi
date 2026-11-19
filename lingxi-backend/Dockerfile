# Lingxi 应用镜像：多阶段构建（编译在构建阶段，运行镜像只留 JRE + jar）
# 构建：docker build -t lingxi-app:local .
# 注意：镜像内不含任何密钥，JWT_SECRET / 数据库与 Redis 密码一律运行时注入（见 compose.yaml 的 app 服务）

# 基础镜像可用构建参数替换，便于内网/离线环境指向自建仓库
ARG BUILD_IMAGE=eclipse-temurin:25-jdk
ARG RUNTIME_IMAGE=eclipse-temurin:25-jre

FROM ${BUILD_IMAGE} AS build
WORKDIR /workspace

# 用仓库自带的 Maven Wrapper：版本锁定在 .mvn/wrapper/maven-wrapper.properties（3.10.0），
# 不用基础镜像 apt 里的 maven——那个版本随发行版漂移，构建不可复现
COPY mvnw ./
COPY .mvn/ .mvn/
COPY pom.xml ./

# 先只拷 pom 预热依赖，源码变动时不必重新下载依赖（利用层缓存）。
# go-offline 偶有插件解析告警，失败不阻断后续真实构建，故允许非零退出
RUN chmod +x mvnw && ./mvnw -B -q -DskipTests dependency:go-offline || true

COPY src ./src
# 构建阶段跳过测试：单元测试不依赖外部服务（mvn test 可单独跑），
# 集成测试需要 Docker/Testcontainers，不该压在镜像构建里。
# 产出一律改名成 app.jar：可执行 jar 的文件名由 artifactId+version 决定，
# 一旦 pom 里改 artifactId，运行阶段的 COPY 通配符就会静默匹配不到（Linux 区分大小写），
# 表现是镜像里没有 jar、容器启动即 "Unable to access jarfile /app/app.jar"。
# target/*.jar 只会匹配可执行 jar，spring-boot 的 *.jar.original 不会被带上。
RUN ./mvnw -B -DskipTests package \
    && cp target/*.jar /workspace/app.jar

FROM ${RUNTIME_IMAGE}
WORKDIR /app

# 非 root 运行：容器逃逸/写文件类风险的最小化措施
# 同时预建日志目录并移交属主：logback-spring.xml 默认写 ./logs（即 /app/logs），
# 不预建的话非 root 用户无法在 /app 下创建目录，启动时会刷 FileNotFoundException
RUN groupadd --system --gid 10001 lingxi \
    && useradd --system --uid 10001 --gid lingxi --home-dir /app --shell /usr/sbin/nologin lingxi \
    && mkdir -p /app/logs \
    && chown -R lingxi:lingxi /app

COPY --from=build --chown=lingxi:lingxi /workspace/app.jar /app/app.jar

USER lingxi
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"
EXPOSE 18080

# JAVA_OPTS 走 shell 展开，便于不改镜像就调堆参数；端口由 SERVER_PORT 环境变量控制
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
