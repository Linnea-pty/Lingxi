package com.linnea.lingxi;

import com.linnea.lingxi.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应用上下文冒烟测试：验证 Spring 容器能在「真实 MySQL + 真实 Redis」下完整装配。
 *
 * <p>WHY 不留在 {@code mvn test} 的单元测试里：它需要拉起容器（十秒级），
 * 而且只验装配、不验分支逻辑；放进集成层由 failsafe 在 verify 阶段执行，
 * 让单元测试保持「无外部依赖、秒级」的口径。</p>
 */
class LingxiApplicationIT extends AbstractIntegrationTest {

    /** 数据源：上下文装配成功后必然可用，用它证明连接详情已由容器注入。 */
    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("上下文装配：数据源连的是容器内的 lingxi 库，且 Flyway 已完成迁移")
    void contextLoads_withRealContainers() {
        // 步骤1：用装配好的数据源执行一次真实查询，确认连接可用
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        Integer businessTableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'lingxi'"
                        + " AND table_name IN ('user', 'post', 'post_like', 'comment')", Integer.class);

        // 步骤2：四张业务表齐全，说明空库从 V1 起按序迁移成功
        assertThat(businessTableCount).isEqualTo(4);

        // 步骤3：断言当前最新迁移（V4）已成功应用，说明建表与变更走的是 Flyway 而非手工脚本。
        // WHY 断言具体版本而不是记录总数：总数每加一个脚本都要改测试，等于把测试变成「抄版本号」；
        // 断言最新版本才真正表达「迁移链执行到了这里」。
        Integer latestVersionAppliedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version = '4'", Integer.class);
        assertThat(latestVersionAppliedCount).isEqualTo(1);
    }
}
