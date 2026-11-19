-- V4：为新增的三类列表查询补索引（只加索引，不改列、不动数据）
-- 背景：新增「某动态的点赞人列表」「某用户赞过的动态」「某用户的评论列表」三个键集分页查询，SQL 形如：
--   post_like WHERE post_id = ? AND status = 1 AND deleted_id = 0 ORDER BY create_time DESC, id DESC
--   post_like WHERE user_id = ? AND status = 1 AND deleted_id = 0 ORDER BY create_time DESC, id DESC
--   comment   WHERE user_id = ? AND is_deleted = 0          ORDER BY create_time DESC, id DESC
-- 现有索引 idx_post_id(post_id) 只能定位、不能提供排序，深翻页会退化成 filesort；
-- 新增索引把「等值列在前、排序列在后」拼齐，游标条件与 ORDER BY 同时命中，避免回表后排序。
-- 保留 idx_post_id 不删：删除索引是独立变更，且取消点赞等写入路径仍在用它，收益不足以承担风险。

ALTER TABLE `post_like`
    ADD INDEX `idx_post_active_time` (`post_id`, `status`, `deleted_id`, `create_time`, `user_id`),
    ADD INDEX `idx_user_active_time` (`user_id`, `status`, `deleted_id`, `create_time`, `post_id`);

ALTER TABLE `comment`
    ADD INDEX `idx_user_time` (`user_id`, `is_deleted`, `create_time`, `id`);
