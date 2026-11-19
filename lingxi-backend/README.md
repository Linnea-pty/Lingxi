# Lingxi 后端

社交动态服务：用户注册登录、动态发布与信息流、点赞、两级评论、关注关系、消息通知、管理员后台，外加 AI Agent 代发（API Key 鉴权）与写接口幂等。

## 技术栈

Spring Boot 4.1 + MyBatis-Plus + MySQL 8.4 + Redis + Spring Security（JWT）+ SpringDoc + Flyway。

## 技术特点

- **双入口鉴权**：Web 端走 JWT（访问/刷新双令牌，刷新即轮换），Agent 代发走 API Key（服务端只存 SHA-256 摘要，明文仅签发时返回一次）。
- **键集游标分页**：全部列表接口不返回页码与总数，`nextCursor` 不透明回传，稳定支撑深翻页与增量加载。
- **写接口幂等**：发布动态 / 评论支持 `requestId`，Redis 幂等栅栏去重；栅栏 fail-open 保证缓存故障不阻断写路径。
- **数据库版本化迁移**：Flyway 按版本号管理表结构，空库与存量库同一条迁移路径，历史脚本不可变。
- **统一返回体**：HTTP 状态只表达传输层语义，业务成败看 `code`，异常与校验错误都有结构化返回。
- **点赞 / 关注靠数据库兜底**：唯一索引 + 状态位保证幂等，不依赖「先查后写」。
- **可复现的容器化与测试**：多阶段构建、非 root 运行、密钥运行时注入；单元测试（`mvn test`）与集成测试（`mvn verify` + Testcontainers）分层。

## 功能模块

接口前缀统一为 `/api/v1`，字段级细节见 [docs/接口清单.md](docs/接口清单.md)。

| 模块 | 入口类 | 能力 |
|---|---|---|
| 认证 | `AuthController` | 注册即登录、登录、刷新令牌（每次换发即轮换）、登出吊销、当前用户信息、Agent API Key 签发/重置 |
| 动态 | `PostController` | 发布（JWT 记 `web`、API Key 记 `agent` 且置顶）、信息流（置顶优先 + 时间倒序）、修改正文、删除（连带评论） |
| 点赞 | `PostLikeController` | 点赞 / 取消（状态位幂等）、点赞人列表 |
| 评论 | `CommentController`、`CommentReplyController` | 两级评论（`parent_id` 定父级、`root_id` 定讨论串）、一级评论列表、讨论串回复列表、级联删除 |
| 个人中心 | `UserCenterController` | 我的评论、我赞过的动态、修改昵称、头像上传（文件头魔数校验 + 落盘） |
| 用户与关注 | `UserController` | 用户主页（含粉丝/关注数与 `isFollowing`）、关注 / 取关（幂等）、关注与粉丝列表 |
| 通知 | `NotificationController` | 通知列表（一并返回未读数）、未读数、单条已读、全部已读 |
| 后台 | `AdminController` | 用户列表（前缀 + 状态筛选）、封禁 / 解封、删除任意动态 / 评论（仅 `ADMIN`，API Key 一律 403） |

## 本地依赖服务（compose.yaml）

```bash
cp .env.example .env    # 首次：填入 MYSQL_ROOT_PASSWORD / REDIS_PASSWORD / JWT_SECRET（.env 已 gitignore）
docker compose up -d    # 默认只起 mysql + redis
```

- MySQL：`127.0.0.1:3306`（只绑回环地址），库名 `lingxi`，密码取 `.env` 的 `MYSQL_ROOT_PASSWORD`；容器只建空库，**表结构与变更由应用启动时的 Flyway 负责**（见下节）。
- Redis：`127.0.0.1:6379`，密码取 `REDIS_PASSWORD`；启动参数带 `--maxmemory-policy noeviction`——里面存的是幂等键与「已吊销令牌」标记，被淘汰会导致已登出的令牌重新可用。
- 数据卷：实际名为 `lingxi_lingxi-mysql-data` / `lingxi_lingxi-redis-data`（项目名 `lingxi` + 卷名）。**不要给卷写显式 `name`**，否则 compose 会挂到另一份空卷上，看起来像数据丢了。
- 应用容器（可选）：`docker compose --profile app up -d --build`，映射 `127.0.0.1:18080`，等 mysql/redis 健康检查通过后才启动；镜像内非 root 运行，日志落 `lingxi-app-logs` 卷。
- 存量数据卷补变更脚本：已由 Flyway 接管，不需要手工执行，见下节。
- 已有手工容器与 compose 冲突时（容器名/端口重复），先 `docker rm -f` 旧容器再起（数据卷保留，不会丢数据）。

## 数据库迁移（Flyway）

脚本在 `src/main/resources/db/migration`，应用启动时按版本号执行，执行记录写入 `flyway_schema_history`（失败即阻断启动——库结构与代码不一致时不该带病运行）。

| 脚本 | 内容 |
|---|---|
| `V1__init_schema.sql` | 四张业务表初始结构（原 `db/schema.sql` 已并入，不再单独存在） |
| `V2__harden_username.sql` | `user.username` 去掉空串默认值 |
| `V3__username_case_sensitive.sql` | `user.username` 列级排序规则改 `utf8mb4_0900_as_cs` |
| `V4__list_query_index.sql` | 点赞人列表 / 我赞过的动态 / 我的评论三类游标查询的联合索引 |
| `V5__user_follow.sql` | 关注关系表（状态位 + 唯一键，取关不释放关系行） |
| `V6__notification.sql` | 通知表（未读 / 全部两套游标索引） |
| `V7__admin_console.sql` | `user` 加 `role` / `status` / `ban_reason` 与注册时间索引 |

两条路径终态一致：

- **空库**：从 V1 按序全量执行到 V7（`mvn verify` 的集成测试走的就是这条路径）。
- **存量库**（表已建、还没有 `flyway_schema_history`）：`baseline-on-migrate` + `baseline-version=3` 把 V1~V3 记为 `BASELINE` 已应用，只执行 V4 及以后。不重放历史脚本的原因：V2 会把排序规则退回 `ai_ci`，在已有仅大小写重名账号的库上必然撞 `uk_username`（实测 `ERROR 1062`）。

**新增变更**：从 `V8__xxx.sql` 起续号，**不要改 V1~V3**——历史脚本必须不可变：改成新内容会让新建环境与老库结构分叉，空库路径下 checksum 不匹配还会直接启动失败。

## 必填环境变量

| 变量 | 说明 |
|---|---|
| `JWT_SECRET` | **必填**，Base64 编码 ≥256bit 随机串；未设置或强度不足时应用启动即失败（Fail-Fast）。本机开发与 compose 都从 `.env` 读 |
| `MYSQL_ROOT_PASSWORD` | **compose 必填**，同时作为 MySQL root 密码与容器内应用的数据源密码；缺失时 compose 直接报错 |
| `REDIS_PASSWORD` | **compose 必填**，应用侧默认值同为 `123456` |
| `SPRINGDOC_ENABLED` | 可选，默认 `true`，生产建议关掉 |
| `MYSQL_PORT` / `REDIS_PORT` / `APP_PORT` | 可选，宿主机映射端口，默认 3306 / 6379 / 18080 |
| `BUILD_IMAGE` / `RUNTIME_IMAGE` | 可选，构建与运行基础镜像，默认 `eclipse-temurin:25-jdk` / `25-jre` |

本地生成密钥并写入不入库的文件：

```powershell
$b=[byte[]]::new(32); $rng=[System.Security.Cryptography.RNGCryptoServiceProvider]::new(); $rng.GetBytes($b)
[Convert]::ToBase64String($b) | Set-Content .jwt-secret -NoNewline
$env:JWT_SECRET = Get-Content .jwt-secret
```

`.jwt-secret` 已在 `.gitignore` 中。**密钥一旦更换，所有已签发令牌立即失效**，客户端需重新登录。

## 启动

```bash
./mvnw spring-boot:run          # 开发
./mvnw -DskipTests package      # 打包
java -jar target/*.jar          # 运行（需先注入 JWT_SECRET）

docker compose --profile app up -d --build   # 全栈容器化（应用 + MySQL + Redis）
```

默认端口 `8080`，本机验证时常用 `--server.port=18080`（容器内固定 `18080`）。

接口文档：`http://localhost:18080/swagger-ui.html`，OpenAPI JSON：`/v3/api-docs`。

## 测试

```bash
./mvnw test      # 纯单元测试，秒级，不依赖任何外部服务
./mvnw verify    # 追加集成测试：Testcontainers 拉起 mysql:8.4 与 redis:8.8.4
```

集成测试需要可用的 Docker；`*IT` 类由 failsafe 在 `verify` 阶段执行，`mvn test` 不会触发。

| 层级 | 用例 | 覆盖点 |
|---|---|---|
| 单元（`*Test`） | `JwtTokenProviderTest`、`TokenStoreTest` | 令牌签发/解析/过期、吊销标记读写 |
| ↳ | `TimeIdCursorTest`、`IdempotentGuardTest` | 游标编解码与非法输入、幂等栅栏命中与 fail-open |
| ↳ | `AuthServiceImplTest`、`PostServiceImplTest`、`CommentServiceImplTest` | 三个核心域的分支逻辑（Mock 依赖） |
| 集成（`*IT`） | `LingxiApplicationIT` | 容器装配 + Flyway 迁移端到端冒烟 |
| ↳ | `AuthTokenFlowIT`、`SecurityChainIT` | 登录 / 刷新轮换 / 登出吊销、白名单与 401/403 统一返回体 |
| ↳ | `UserFollowIT`、`NotificationIT` | 关注幂等与游标列表、通知产生规则与已读 |
| ↳ | `PostModerationIT`、`AdminConsoleIT` | 动态 / 评论删除与列表口径、后台封禁解封与越权拦截 |
| ↳ | `UserUsernameCollationIT` | 用户名大小写敏感排序规则在真实 MySQL 上的行为 |

## 关键约定

- **统一返回体**：HTTP 状态只表达传输层语义（200/401/403/500），业务成败一律看 `code`，`"0"` 为成功，其余见 `ErrorCode`。前端按 `code` 分支，不解析 `message`。
- **分页一律键集游标**：不返回页码与总数，`nextCursor` 对前端不透明，原样回传给下次请求；为 `null` 表示末页。
- **写接口幂等**：发布动态与发表评论支持 `requestId`（≤64 位，字母数字下划线与中划线）。同一用户同一键重复提交返回首次结果；不带则不去重。并发同键且首个请求未完成时返回 `COMMON_DUPLICATE_REQUEST`。
- **幂等栅栏 fail-open**：Redis 不可用时降级为不去重并打印 `ERROR` 日志（这是刻意取舍：不因缓存故障让发布/评论不可用）。**出现该日志应立即恢复 Redis**，降级窗口内重复提交会落多条数据。
- **点赞幂等由数据库兜底**：唯一索引 `uk_user_post_deleted`，不依赖「先查后写」。
- **评论树**：`parent_id=0` 为一级评论，`root_id` 标识讨论串；一级评论 `root_id` 同事务回填为自身 ID，回复继承父评论 `root_id`，父评论必须属于同一动态。
- **关注幂等**：`user_follow` 用「状态位 + 唯一键」而非逻辑删除 + 重插（后者取关后再关注会撞 `uk_user_follow`）；重复关注只刷新 `follow_time`，重复取关影响 0 行，均返回成功。
- **通知自动产生、不做合并**：点赞 / 评论 / 回复 / 关注四类事件各生成一条通知；自己操作自己不产生；已读接口对「不存在 / 非本人 / 已读」一律返回成功，避免用错误码探测他人通知。
- **头像上传**：`/uploads/**` 为公开静态映射（浏览器 `<img>` 不带令牌）；类型按文件头魔数判定，不认扩展名与 `Content-Type`；写库成功后才删旧文件。
- **后台权限**：`/admin/**` 类级 `@PreAuthorize("hasRole('ADMIN')")`，API Key 一律 403；管理员无注册入口，只能改库产生；封禁立即生效（认证链路每次请求回查账号状态）。

## 目录结构

```
src/main/java/com/linnea/lingxi
├── common/
│   ├── web/           Response 统一返回体
│   ├── exception/     ErrorCode、业务/系统异常、全局异常处理
│   ├── cursor/        TimeIdCursor、FeedCursor（键集游标编解码）
│   ├── idempotent/    IdempotentGuard（Redis 幂等栅栏）
│   ├── file/          AvatarStorage（魔数校验 + 落盘）
│   └── enums/         UserRole、UserStatus、NotificationType
├── config/        Security、WebMvc（/uploads 静态映射）、MyBatis-Plus 分页插件
├── controller/    REST 入口（只做参数校验与日志，不写业务）
├── dto/           入参对象（Controller 收参，带校验注解）
├── vo/            出参对象（Entity 不出 Controller）
├── entity/ mapper/ 表映射与数据访问，复杂 SQL 在 resources/mapper/*.xml
├── security/      JWT 与 API Key 过滤器、令牌吊销存储、API Key 摘要编解码
└── service/       业务接口 + impl 实现

src/main/resources
├── db/migration/  Flyway 脚本 V1~V7
├── mapper/        6 个 Mapper XML（user / post / comment / post_like / user_follow / notification）
├── application.properties / logback-spring.xml

src/test/java/com/linnea/lingxi
├── *Test          单元测试（mvn test，无外部依赖）
└── *IT            集成测试（mvn verify，Testcontainers 真库）
```

## 已知限制

- 吊销标记依赖 Redis 查询，Redis 不可用时按 fail-open 放行（见 `TokenStore`），登出后的令牌可能在该窗口内仍可用。
- 信息流与点赞数用批量子查询现算，未做缓存聚合。
- **列表口径与计数口径刻意分开**：点赞人 / 关注 / 粉丝列表不展示已注销账号，但 `likeCount` / `followerCount` 仍统计这些行为记录——不要拿列表长度去校验计数。
- 通知表只追加、不做合并，也没有归档清理任务（`idx_create_time` 已预留，供后续按时间清理）。
- 历史脚本 V1~V3 不可重放（V2 会把 `username` 退回大小写不敏感），存量库靠 baseline 跳过；若 `flyway_schema_history` 被人为清空，需先确认库已到 V3 再让应用启动，否则会撞 `uk_username`。
- 容器镜像未接 registry 发布流程，只有本地 `lingxi-app:local`。
