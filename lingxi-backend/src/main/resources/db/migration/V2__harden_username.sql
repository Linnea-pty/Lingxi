-- ============================================================
-- 变更：user.username 去掉空串默认值
-- 原因：NOT NULL + DEFAULT '' + 唯一索引 => 最多只允许一行空串，
--       漏传 username 时第一条 INSERT 会静默成功，第二条才报 1062，误导排查
-- 影响：仅改列默认值，不改类型/长度/索引，存量数据不受影响
-- 执行前置检查（预期 0 行）：
--   SELECT id, username FROM `user` WHERE username = '';
-- ============================================================

ALTER TABLE `user`
    MODIFY COLUMN `username` VARCHAR(64) NOT NULL COMMENT '登录用户名，全局唯一（写入前必须规范化：NFKC + trim + 小写，禁止空串）';
