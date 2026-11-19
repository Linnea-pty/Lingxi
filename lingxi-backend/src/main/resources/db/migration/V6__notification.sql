-- ============================================================
-- V6：消息通知表 / MySQL 8.4
-- 接收者维度查询：列表（全部/仅未读）+ 未读数
--
-- 设计要点：
-- ① 一条「事件」生成一条通知，不做「同人同目标只留一条」的合并——合并需要额外 UPDATE + 时间刷新，
--    而通知是追加型数据，重复点赞/重复评论本就产生多条事件，用户看到 2 条「赞了你的动态」不算错。
-- ② 自通知（自己赞/评论自己的动态、关注自己）在业务层过滤，不入库，避免库里堆积无人会读的行。
-- ③ 两套索引都是「等值列在前、游标列在后」，让 WHERE + ORDER BY 共用同一索引，避免额外排序：
--    unreadOnly=true  → idx_user_read_time (user_id, is_read, create_time, id)
--    unreadOnly=false → idx_user_time      (user_id, create_time, id)
-- ④ 通知表会持续增长，idx_create_time 供后续按时间归档/清理（本次不实现清理任务）。
-- ============================================================

CREATE TABLE `notification` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID（同时作为键集分页游标的第二排序键）',
    `user_id`     BIGINT UNSIGNED NOT NULL                COMMENT '接收者用户ID，关联 user.id',
    `actor_id`    BIGINT UNSIGNED NOT NULL                COMMENT '触发者用户ID，关联 user.id（谁点的赞/谁评的论/谁关注了你）',
    `type`        VARCHAR(32)     NOT NULL                COMMENT '通知类型：LIKE_POST-赞了动态 COMMENT_POST-评论了动态 REPLY_COMMENT-回复了评论 FOLLOW-关注了你',
    `target_id`   BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '业务目标ID：LIKE_POST/COMMENT_POST/REPLY_COMMENT 为评论或动态ID，FOLLOW 为关注发起方用户ID',
    `post_id`     BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '关联动态ID，仅供前端跳转，0 表示与动态无关（如关注）',
    `is_read`     TINYINT         NOT NULL DEFAULT 0      COMMENT '已读标记：0-未读 1-已读',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（业务本地时区，兼作通知时间的展示值）',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（业务本地时区，标记已读时刷新）',
    PRIMARY KEY (`id`),
    KEY `idx_user_read_time` (`user_id`, `is_read`, `create_time`, `id`),
    KEY `idx_user_time` (`user_id`, `create_time`, `id`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '消息通知表';
