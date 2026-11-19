-- ============================================================
-- 变更：user.username 比较改为大小写敏感（case-sensitive）
-- 原因：原列沿用表级 utf8mb4_0900_ai_ci（大小写不敏感），Alice 与 alice 被视为同一用户名：
--       先注册 Alice 后注册 alice 会被唯一键拒绝，且 alice 可用自己的密码登录 Alice 的账号，
--       与「用户名区分大小写」口径不符。
-- 做法：仅改该列排序规则为 utf8mb4_0900_as_cs（大小写敏感 + 重音敏感）。
--       用户名白名单只有 ASCII 字母/数字/下划线，重音敏感性无实际影响；
--       类型、长度、默认值、唯一索引 uk_username 均不变，索引随列自动按新排序规则比较。
-- 影响：存量数据必须满足「不存在仅大小写差异的重名」，否则 ALTER 会因唯一键冲突失败。
-- 执行前置检查（预期 0 行）：
--   SELECT LOWER(username) AS low_name, COUNT(*) AS dup_count
--   FROM `user` GROUP BY low_name HAVING COUNT(*) > 1;
-- 回滚（执行前需确认无仅大小写差异的数据，否则会撞唯一键）：
--   ALTER TABLE `user` MODIFY COLUMN `username` VARCHAR(64)
--       COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '登录用户名，全局唯一且注销后不释放（不允许复用，防冒名）';
-- 注意：本语句会覆盖列注释，V2 中「写入前必须规范化：NFKC + trim + 小写」的旧口径随之作废，
--       现行口径是「只做 trim，不归一大小写」。
-- ============================================================

ALTER TABLE `user`
    MODIFY COLUMN `username` VARCHAR(64)
        COLLATE utf8mb4_0900_as_cs
        NOT NULL
        COMMENT '登录用户名，全局唯一且注销后不释放（不允许复用，防冒名）；比较区分大小写（列级 as_cs），写入前只做 trim，不归一大小写';
