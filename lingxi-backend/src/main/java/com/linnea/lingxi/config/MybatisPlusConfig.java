package com.linnea.lingxi.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 全局配置：Mapper 扫描与拦截器链。
 *
 * <p>拦截器顺序不可随意调整：会改写 SQL 的拦截器（多租户、数据权限、动态表名）必须排在分页插件之前，
 * 否则 COUNT 语句不会被改写，分页 total 不准确。</p>
 */
@Slf4j
@Configuration
@MapperScan("com.linnea.lingxi.mapper")
public class MybatisPlusConfig {

    /** 单页最大条数上限，防止外部传入超大分页参数拖垮数据库。 */
    private static final long MAX_PAGE_SIZE = 100L;

    /**
     * 构建 MyBatis-Plus 拦截器容器。
     *
     * @return 已按正确顺序注册防全表攻击与分页插件的拦截器容器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 步骤1：注册防全表更新/删除拦截器，拦截不带 WHERE 的 update、delete 语句
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        // 步骤2：注册分页插件，显式指定 MySQL 方言并限制单页上限；必须最后添加
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(MAX_PAGE_SIZE);
        interceptor.addInnerInterceptor(pagination);
        log.info("MyBatis-Plus 拦截器注册完成：BlockAttackInnerInterceptor + PaginationInnerInterceptor(MYSQL, maxLimit={})",
                MAX_PAGE_SIZE);
        return interceptor;
    }
}
