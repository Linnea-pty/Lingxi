-- ============================================================
-- V1：Lingxi（灵犀）初始化表结构 / MySQL 8.4
-- 执行方：应用启动时由 Flyway 执行（classpath:db/migration），不再由容器 initdb 或手工脚本执行
-- 引擎 InnoDB，字符集 utf8mb4，表级排序规则 utf8mb4_0900_ai_ci
-- 用户名策略：注销不释放 username（不允许复用），同名注册一律拒绝；Alice 与 alice 是两个账号
--
-- 【不要修改本文件】已执行的迁移是历史，改了会让新环境与老库结构分叉：空库会按新内容建表，
-- 空库路径下 checksum 不匹配还会直接启动失败（存量库 baseline 后 V1~V3 属于 below baseline，不再执行也不再校验）。
-- 新增变更一律写 V4、V5……；V2/V3 是对 user.username 的历史补丁（V2 去空串默认值，
-- V3 改列级排序规则为 utf8mb4_0900_as_cs），此处记录的已是补丁后的最终定义，净效果与 V3 一致。
-- 库本身由连接串或 compose 的 MYSQL_DATABASE 建好，脚本内不再 CREATE DATABASE / USE。
-- ============================================================

CREATE TABLE `user` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `username`      VARCHAR(64)     COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '登录用户名，唯一且注销后不释放，区分大小写；写入前仅 trim 不归一；无默认值',
    `password_hash` VARCHAR(255)    NOT NULL DEFAULT ''       COMMENT '密码哈希（加盐哈希后的密文，禁止存明文）',
    `nickname`      VARCHAR(64)     NOT NULL DEFAULT ''       COMMENT '昵称，用于页面展示',
    `avatar_url`    VARCHAR(512)    NOT NULL DEFAULT ''       COMMENT '头像图片地址，空串表示未设置',
    `api_key`       VARCHAR(128)    NOT NULL DEFAULT ''       COMMENT 'Agent 鉴权密钥的 SHA-256 摘要（64位十六进制），空串表示未开通；库内不存明文',
    `is_deleted`    TINYINT         NOT NULL DEFAULT 0        COMMENT '逻辑删除：0-未删除 1-已删除',
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（业务本地时区）',
    `update_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（业务本地时区）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_api_key` (`api_key`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户表';

CREATE TABLE `post` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID（同时作为信息流游标排序字段）',
    `user_id`     BIGINT UNSIGNED NOT NULL                COMMENT '发布者用户ID，关联 user.id',
    `content`     TEXT            NOT NULL                COMMENT '动态正文',
    `source`      VARCHAR(16)     NOT NULL DEFAULT 'web'  COMMENT '发布来源：web-网页端发布，agent-AI助手代发',
    `is_pinned`   TINYINT         NOT NULL DEFAULT 0      COMMENT '是否置顶：0-否 1-是（Agent 代发动态置 1）',
    `is_deleted`  TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（业务本地时区）',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（业务本地时区）',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_feed_cursor` (`is_pinned`, `create_time`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '动态表';

CREATE TABLE `post_like` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID（取消点赞时用于回填 deleted_id）',
    `user_id`     BIGINT UNSIGNED NOT NULL                COMMENT '点赞用户ID，关联 user.id',
    `post_id`     BIGINT UNSIGNED NOT NULL                COMMENT '被点赞动态ID，关联 post.id',
    `status`      TINYINT         NOT NULL DEFAULT 1      COMMENT '点赞状态：1-有效点赞 0-已取消（业务查询条件）',
    `deleted_id`  BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '软删除标记：未删除固定为0，取消点赞时置为本行id或任意非0值，用于释放活跃唯一位',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（业务本地时区）',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（业务本地时区）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_post_deleted` (`user_id`, `post_id`, `deleted_id`),
    KEY `idx_post_id` (`post_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '动态点赞表';

CREATE TABLE `comment` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `post_id`       BIGINT UNSIGNED NOT NULL                COMMENT '所属动态ID，关联 post.id',
    `user_id`       BIGINT UNSIGNED NOT NULL                COMMENT '评论人用户ID，关联 user.id',
    `parent_id`     BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '父评论ID：0-一级评论，否则为直接父评论ID（用于前端还原层级）',
    `root_id`       BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '所属讨论串根评论ID：一级评论的root_id等于自身id，回复继承父评论的root_id',
    `reply_user_id` BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '被回复人用户ID，0-无（一级评论或未指定）',
    `content`       TEXT            NOT NULL                COMMENT '评论正文',
    `is_deleted`    TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（业务本地时区）',
    `update_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（业务本地时区）',
    PRIMARY KEY (`id`),
    KEY `idx_post_id` (`post_id`),
    KEY `idx_root_time` (`root_id`, `create_time`, `id`),
    KEY `idx_post_time` (`post_id`, `create_time`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '评论表';
