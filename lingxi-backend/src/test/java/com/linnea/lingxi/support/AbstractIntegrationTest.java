package com.linnea.lingxi.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

/**
 * 集成测试基类：自管 MySQL 与 Redis 容器，不依赖本机已启动的服务。
 *
 * <p>WHY 必须用真实数据库：{@code user.username} 的列级排序规则 utf8mb4_0900_as_cs（大小写敏感）
 * 与 uk_username、uk_user_post_deleted 两个唯一键都是 MySQL 方言特性，H2 之类替身无法复现，
 * 换成替身只会得到「看着绿、上线红」的假阳性。</p>
 *
 * <p>WHY 用单例模式（静态初始化块启动）而不是 {@code @Container}：JUnit 的 Testcontainers 扩展
 * 会在每个测试类的 afterAll 停掉静态容器，第一个类跑完容器就没了，第二个类只能连到已经关闭的端口。
 * 单例模式下容器随 JVM 存活，Ryuk 在进程退出时统一回收，多个测试类共享同一套真实依赖，
 * 既可以跨类复用 Spring 上下文，也不必为每个类付一次启动成本。</p>
 *
 * <p>镜像 tag 与 compose.yaml 保持一致（mysql:8.4 / redis:8.8.4），
 * 避免测出来的行为和本地运行环境不是同一套方言与命令语义。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    /** MySQL 镜像，与 compose.yaml 的 mysql 服务对齐。 */
    private static final String MYSQL_IMAGE = "mysql:8.4";

    /** Redis 镜像，与 compose.yaml 的 redis 服务对齐。 */
    private static final String REDIS_IMAGE = "redis:8.8.4";

    /** 容器内数据库名，与 compose 的 MYSQL_DATABASE 一致；Flyway 在该库内建表。 */
    private static final String DATABASE_NAME = "lingxi";

    /** MySQL 默认端口。 */
    private static final int MYSQL_PORT = 3306;

    /** 容器内 Redis 端口。 */
    private static final int REDIS_PORT = 6379;

    /** 测试用 Redis 密码，与 application.properties 的 REDIS_PASSWORD 默认值一致。 */
    private static final String REDIS_PASSWORD = "123456";

    /**
     * MySQL 容器：只建空库，不预执行任何 DDL。
     *
     * <p>WHY 交给 Flyway 而不是 {@code withInitScript}：建表路径必须与生产一致，
     * 否则「迁移脚本写错」这类问题只会在上线时暴露——手工初始化脚本永远绕过 Flyway 的版本与校验。</p>
     */
    protected static final MySQLContainer MYSQL_CONTAINER = new MySQLContainer(DockerImageName.parse(MYSQL_IMAGE))
            .withDatabaseName(DATABASE_NAME);

    /**
     * Redis 容器：开启密码认证，与本地 compose 的口径一致，
     * 这样幂等栅栏走的是真实的 AUTH + SET NX 路径，而不是无密码的宽松环境。
     */
    protected static final GenericContainer<?> REDIS_CONTAINER =
            new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                    .withExposedPorts(REDIS_PORT)
                    .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);

    static {
        // 步骤1：并行启动两个容器，任一失败即让类初始化失败，避免后续断言报出误导性的连接错误
        Startables.deepStart(List.of(MYSQL_CONTAINER, REDIS_CONTAINER)).join();
    }

    /**
     * 把容器的实际连接信息注入 Spring 环境。
     *
     * <p>显式注册而不是依赖 {@code @ServiceConnection}：后者要求容器字段同时是 {@code @Container} 托管字段，
     * 与上面的单例模式冲突；这里把 url/用户名/密码一次写清楚，也便于排查「连的到底是不是容器」。</p>
     *
     * @param registry Spring 动态属性注册表
     */
    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        // 步骤1：数据源指向容器映射端口，参数与 application.properties 保持一致（禁用 SSL、允许取公钥、固定时区）
        registry.add("spring.datasource.url", () -> "jdbc:mysql://" + MYSQL_CONTAINER.getHost() + ":"
                + MYSQL_CONTAINER.getMappedPort(MYSQL_PORT) + "/" + DATABASE_NAME
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=Asia/Shanghai");
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        // 步骤2：Redis 指向容器主机与映射端口，密码用容器启动时设置的那个，避免两边漂移
        registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
        registry.add("spring.data.redis.port", () -> REDIS_CONTAINER.getMappedPort(REDIS_PORT));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
        // 步骤3：头像落盘目录指向 target 下，避免测试往项目根目录写文件、污染工作区
        registry.add("app.upload.avatar-dir", () -> "target/test-uploads");
    }
}
