package com.linnea.lingxi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linnea.lingxi.entity.User;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户表数据访问入口。
 *
 * <p>单表增删改查由 {@link BaseMapper} 提供。需要联表、聚合、窗口函数或数据库专有函数时，
 * 在本接口声明方法并在同名 XML（resources/mapper/UserMapper.xml）中实现，
 * 禁止用 Wrapper 的 apply/last 拼接 SQL 片段。</p>
 */
public interface UserMapper extends BaseMapper<User> {

    /**
     * 后台用户列表：按注册时间倒序的键集分页，支持关键字前缀与状态筛选。
     *
     * @param keyword          用户名/昵称前缀关键字，已由服务层转义通配符，可为 null
     * @param status           账号状态筛选，1-正常 0-封禁，null 表示不筛选
     * @param cursorCreateTime 游标中的注册时间，首页为 null
     * @param cursorId         游标中的用户ID，首页为 null
     * @param limit            查询条数，服务层传「每页条数 + 1」用于探测下一页
     * @return 用户实体列表，按 (create_time, id) 倒序
     */
    List<User> selectAdminUserList(@Param("keyword") String keyword,
                                   @Param("status") Integer status,
                                   @Param("cursorCreateTime") LocalDateTime cursorCreateTime,
                                   @Param("cursorId") Long cursorId,
                                   @Param("limit") int limit);
}
