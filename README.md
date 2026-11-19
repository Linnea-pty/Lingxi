# 灵犀（Lingxi）

灵犀是一款前后端分离的社交动态服务：用户注册登录、动态发布与信息流、点赞、两级评论、关注关系、消息通知、管理员后台，以及 AI Agent 代发（API Key 鉴权）与写接口幂等。

- 后端：`lingxi-backend`（Spring Boot 4.1），提供 REST API，见 [lingxi-backend/README.md](lingxi-backend/README.md)
- 前端：`lingxi-frontend`（Vue 3 + TypeScript + Vite），提供 Web 界面，见 [lingxi-frontend/README.md](lingxi-frontend/README.md)

## 版本

| 模块 | 版本 |
|---|---|
| 后端（lingxi-backend） | 0.0.1-SNAPSHOT |
| 前端（lingxi-frontend） | 0.0.0 |

> 当前处于开发阶段（预发布）。

## 实现的功能

| 模块 | 功能 |
|---|---|
| 认证 | 注册即登录、登录、刷新令牌（每次换发即轮换）、登出吊销、当前用户、Agent API Key 签发 / 重置 |
| 动态 | 发布（Web / Agent 置顶）、信息流（置顶优先 + 时间倒序）、修改正文、删除 |
| 点赞 | 点赞 / 取消（幂等）、点赞人列表 |
| 评论 | 两级评论（一级 + 讨论串回复）、级联删除 |
| 个人中心 | 我的评论、我赞过的动态、修改昵称、头像上传（文件头魔数校验） |
| 用户与关注 | 用户主页、关注 / 取关（幂等）、关注与粉丝列表 |
| 通知 | 通知列表、未读数、单条已读、全部已读 |
| 后台 | 用户列表与筛选、封禁 / 解封、删除任意动态 / 评论（仅 `ADMIN`） |

## 开发环境

| 项目 | 要求 |
|---|---|
| 后端 | JDK 25、Maven 3.10（自带 Maven Wrapper，无需手动安装 Maven）、Docker |
| 前端 | Node.js 22.18+ 或 24.12+、npm |
| 依赖服务 | MySQL 8.4、Redis 8.8.4（由 `docker compose` 一键拉起） |

## 本地拉取代码的步骤

### 1. 获取代码

```bash
git clone <仓库地址> lingxi
cd lingxi
```

> 仓库内含前后端两个独立目录：`lingxi-backend` 与 `lingxi-frontend`。

### 2. 启动后端依赖（MySQL + Redis）

```bash
cd lingxi-backend
cp .env.example .env    # 填入 MYSQL_ROOT_PASSWORD / REDIS_PASSWORD / JWT_SECRET
docker compose up -d    # 只起 mysql + redis
```

### 3. 启动后端

```bash
./mvnw spring-boot:run
```

- 接口文档：`http://localhost:18080/swagger-ui.html`
- OpenAPI JSON：`http://localhost:18080/v3/api-docs`

### 4. 启动前端

```bash
cd ../lingxi-frontend
npm install
npm run dev
```

浏览器访问 Vite 输出的本地地址（默认 `http://localhost:5173`），后端接口根路径缺省为 `http://localhost:18080/api/v1`（可通过前端 `.env.local` 的 `VITE_API_BASE_URL` 覆盖）。

> 更完整的说明（接口清单、环境变量、测试、目录结构）见各自子目录 README。
