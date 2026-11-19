-- 管理员后台：角色、封禁状态与封禁原因。
-- 授权方式：本迁移只加字段，不预设管理员。上线后由运维执行
--   UPDATE `user` SET role = 'ADMIN' WHERE username = '目标用户名';
-- 提升账号权限（不做「配置项自动提权」：配置写错就等于把任意账号变成管理员）。
ALTER TABLE `user`
    ADD COLUMN `role`       VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT '角色：USER-普通用户 ADMIN-管理员；仅在认证签发时决定授予的权限，改库后重新登录生效' AFTER `avatar_url`,
    ADD COLUMN `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '账号状态：1-正常 0-封禁；封禁后该账号的旧访问令牌与 Agent 密钥在下一次请求回查时立即失效' AFTER `role`,
    ADD COLUMN `ban_reason` VARCHAR(200) NOT NULL DEFAULT ''     COMMENT '封禁原因（运营备注），空串表示未封禁' AFTER `status`,
    ADD KEY `idx_create_time` (`create_time`, `id`);

-- 说明：
-- 1) 管理员列表按注册时间倒序翻页，游标是 (create_time, id)，与 idx_create_time 完全对齐；
-- 2) status 只有 0/1 且绝大多数为 1，区分度极低，不单独建索引：按状态过滤时走 idx_create_time 范围扫描 + 回表判断即可，
--    单独建索引反而增加每次写入的维护成本（索引规范：低区分度列不单独建索引）。
