# 灵犀前端

灵犀社交应用的前端（Vue 3 + TypeScript + Vite + Pinia + Vue Router），页面完全对齐《前后端联调接口清单》。

## 技术栈

- **框架**：Vue 3.5（Composition API + `<script setup>`）+ TypeScript 5.9 + Vite 8
- **状态管理**：Pinia 4（`auth` / `notification` 两个 store）
- **路由**：Vue Router 5（路由懒加载 + 守卫，`requiresAuth` / `guestOnly` / `requiresAdmin`）
- **工程化**：ESLint 10 + Oxlint + Prettier、vue-tsc 类型检查、Vitest + Vue Test Utils

## 页面

| 路由 | 页面 | 对应接口 |
|---|---|---|
| `/login`、`/register` | 登录 / 注册 | `POST /auth/login`、`POST /auth/register` |
| `/` | 动态广场（发布 + 信息流） | `POST /posts`、`GET /posts/feed`、点赞 |
| `/posts/:postId` | 动态详情（评论 / 回复 / 点赞人） | `GET /posts/{id}/comments`、`GET /comments/{rootId}/replies`、`GET /posts/{id}/likes` |
| `/users/:userId` | 用户主页（关注 / 粉丝） | `GET /users/{id}`、follow、(un)following、(un)followers |
| `/me` | 个人中心（资料 / 头像 / Agent 密钥 / 我的评论 / 我赞过的） | `/users/me*`、`/auth/api-key/rotate` |
| `/notifications` | 消息通知（全部 / 只看未读） | `/notifications*` |
| `/admin/users` | 用户管理（仅 ADMIN） | `/admin/users*` |

后台删除动态 / 评论复用广场与详情页卡片上的「删除」，管理员登录后即对任意内容可见。

## 技术特点

- **统一返回体**：`src/api/http.ts` 只按 `code` 判断成败，`"0"` 成功；非 0 抛 `ApiError`，`message` 直接展示。
- **401 自动续期**：先用 `refreshToken` 调 `/auth/refresh` 并重放原请求，同一时刻只有一个刷新请求在飞行；刷新失败才清本地令牌跳登录页。`403` 不触发刷新。
- **键集游标**：`src/composables/useCursorList.ts` 只认 `nextCursor` + `hasMore`，不自己算页码；「我的评论」「我赞过的」各自独立游标。
- **幂等键**：发布动态 / 评论时一次动作生成一个 `requestId`，失败重试复用，成功后清空（`src/utils/requestId.ts`）。
- **点赞 / 关注**：直接用响应里的 `liked` / `likeCount`、`following` / `followerCount` 覆盖本地，不做自增自减。
- **通知角标**：`unreadCount` 只用服务端返回值覆盖；列表接口返回的角标顺带更新，另有 60s 轮询。
- **评论区排序**：一级评论倒序、讨论串回复正序，两套游标分开管理。
- **头像**：`FormData` 字段名固定 `file`，成功后用返回的 `avatarUrl` 拼静态域名展示。

> 清单里没有「单条动态查询」接口，动态详情页优先使用列表跳转时携带的 `state.post`；直接打开详情页（如从通知进入）时回退为在信息流里向前翻找。

## 环境变量

```sh
# .env.local
VITE_API_BASE_URL=http://localhost:18080/api/v1
```

缺省即 `http://localhost:18080/api/v1`；头像等静态资源域名由该地址去掉 `/api/v1` 得到。若后端未开放 CORS，可在 `vite.config.ts` 里配 `server.proxy`，或让后端允许开发服地址。

## Project Setup

```sh
npm install
```

### Compile and Hot-Reload for Development

```sh
npm run dev
```

### Type-Check, Compile and Minify for Production

```sh
npm run build
```

### Run Unit Tests with [Vitest](https://vitest.dev/)

```sh
npm run test:unit
```

### Lint with [ESLint](https://eslint.org/)

```sh
npm run lint
```
