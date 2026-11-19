-- V5：新增用户关注关系表（只新建表，不改既有列、不动既有数据）
-- 设计取舍：一对 (user_id, follow_user_id) 只保留一行，用 status 表达关注/取关，而不是逻辑删除 + 重新插入。
-- 原因：逻辑删除的旧行仍占用唯一键，取关后再关注会撞 uk_user_follow；用状态位则关注是
--   INSERT ... ON DUPLICATE KEY UPDATE status = 1（天然幂等），取关是 UPDATE status = 0（重复调用影响 0 行），
--   与本项目 post_like 的「状态位 + 唯一键」口径一致。
-- follow_time 单独一列而不是复用 create_time：重新关注需要把排序键刷新到最新，
--   而 create_time 的语义是「首次建立关系」，不该被改写。

CREATE TABLE `user_follow` (
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID（关注关系游标排序字段）',
    `user_id`        BIGINT UNSIGNED NOT NULL                COMMENT '关注发起方用户ID，关联 user.id',
    `follow_user_id` BIGINT UNSIGNED NOT NULL                COMMENT '被关注方用户ID，关联 user.id',
    `status`         TINYINT         NOT NULL DEFAULT 1      COMMENT '关注状态：1-已关注 0-已取关（业务查询条件）',
    `follow_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次关注时间（重新关注会刷新），列表排序与游标键',
    `create_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（业务本地时区）',
    `update_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（业务本地时区）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_follow` (`user_id`, `follow_user_id`),
    KEY `idx_following_time` (`user_id`, `status`, `follow_time`, `id`),
    KEY `idx_follower_time` (`follow_user_id`, `status`, `follow_time`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户关注关系表';
