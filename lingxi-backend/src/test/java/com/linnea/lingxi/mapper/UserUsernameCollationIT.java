package com.linnea.lingxi.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linnea.lingxi.entity.User;
import com.linnea.lingxi.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用户名大小写敏感策略的集成测试：验证的是 MySQL 的列排序规则与唯一键，属于方言特性。
 *
 * <p>WHY 不能写成单元测试：{@code Alice} 与 {@code alice} 能否并存完全由列的排序规则
 * utf8mb4_0900_as_cs 决定，替身数据库（H2）不复现该行为，用替身断言等于什么都没验。</p>
 *
 * <p>测试类加 {@code @Transactional}：MOCK 模式的上下文里测试事务能覆盖 Service 事务，
 * 每个用例结束自动回滚，用例之间不残留用户数据。本类只断言数据库约束，不验证业务回滚语义，
 * 因此不受「测试级事务让回滚断言恒真」的陷阱影响。</p>
 */
@Transactional
class UserUsernameCollationIT extends AbstractIntegrationTest {

    /** 测试用大驼峰用户名。 */
    private static final String UPPER_CASE_USERNAME = "Alice";

    /** 仅大小写不同的用户名。 */
    private static final String LOWER_CASE_USERNAME = "alice";

    /** 用户数据访问：走真实的 MyBatis 与数据库排序规则。 */
    @Autowired
    private UserMapper userMapper;

    /** 直接执行 SQL：用于断言列元数据与唯一键冲突，绕开业务层的查重预校验。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("列级排序规则：user.username 为 utf8mb4_0900_as_cs，其余列仍用表级 ai_ci")
    void usernameColumn_usesCaseSensitiveCollation() {
        // 执行：读取 user 表所有列的排序规则
        String usernameCollation = queryCollation("username");
        String nicknameCollation = queryCollation("nickname");

        // 断言：只有用户名列区分大小写，昵称等展示字段仍按表级不区分大小写比较
        assertThat(usernameCollation).isEqualTo("utf8mb4_0900_as_cs");
        assertThat(nicknameCollation).isEqualTo("utf8mb4_0900_ai_ci");
    }

    @Test
    @DisplayName("仅大小写不同：Alice 与 alice 可以并存，是两个独立账号")
    void insertCaseOnlyDifference_isAllowed() {
        // 执行：同一字母序列的两种大小写各注册一次
        insertUser(UPPER_CASE_USERNAME);
        insertUser(LOWER_CASE_USERNAME);

        // 断言：没有撞唯一键，两条记录都在
        assertThat(countByUsername(UPPER_CASE_USERNAME)).isEqualTo(1);
        assertThat(countByUsername(LOWER_CASE_USERNAME)).isEqualTo(1);
    }

    @Test
    @DisplayName("完全同名：被 uk_username 拒绝，用户名不释放的语义由此兜底")
    void insertSameUsername_isRejectedByUniqueKey() {
        // 前置：先占用用户名
        insertUser(UPPER_CASE_USERNAME);

        // 执行 + 断言：第二次插入必须撞唯一键，而不是静默产生两个同名账号
        assertThatThrownBy(() -> insertUser(UPPER_CASE_USERNAME))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(countByUsername(UPPER_CASE_USERNAME)).isEqualTo(1);
    }

    @Test
    @DisplayName("按用户名查询：区分大小写，用 alice 查不到 Alice")
    void selectByUsername_isCaseSensitive() {
        // 前置：只注册大驼峰账号
        insertUser(UPPER_CASE_USERNAME);

        // 执行
        User matchedUser = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, UPPER_CASE_USERNAME));
        User mismatchedUser = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, LOWER_CASE_USERNAME));

        // 断言：精确大小写命中，换成小写则查不到（一旦查得到，登录就会登进别人的账号）
        assertThat(matchedUser).isNotNull();
        assertThat(matchedUser.getUsername()).isEqualTo(UPPER_CASE_USERNAME);
        assertThat(mismatchedUser).isNull();
    }

    /**
     * 读取 user 表指定列的排序规则。
     *
     * @param columnName 列名
     * @return 列的排序规则名
     */
    private String queryCollation(String columnName) {
        // 步骤1：从 information_schema 读列元数据，避免用 SHOW FULL COLUMNS 输出难解析
        return jdbcTemplate.queryForObject(
                "SELECT COLLATION_NAME FROM information_schema.columns"
                        + " WHERE table_schema = 'lingxi' AND table_name = 'user' AND column_name = ?",
                String.class, columnName);
    }

    /**
     * 直接插入一条用户记录，绕开业务层查重，用于验证数据库约束本身。
     *
     * @param username 用户名
     */
    private void insertUser(String username) {
        // 步骤1：只填必填列，password_hash 在 DDL 中有默认空串，此处显式给占位值更贴近真实数据
        jdbcTemplate.update("INSERT INTO `user` (username, password_hash, nickname) VALUES (?, ?, ?)",
                username, "$2a$10$placeholder-placeholder-placeholder", username);
    }

    /**
     * 统计指定用户名的记录数（区分大小写）。
     *
     * @param username 用户名
     * @return 记录数
     */
    private int countByUsername(String username) {
        // 步骤1：按用户名精确计数，username 列自身区分大小写，无需额外转换
        Integer recordCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `user` WHERE username = ?", Integer.class, username);
        return recordCount == null ? 0 : recordCount;
    }
}
