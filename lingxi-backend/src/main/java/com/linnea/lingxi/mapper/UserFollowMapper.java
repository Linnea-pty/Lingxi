package com.linnea.lingxi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linnea.lingxi.entity.UserFollow;
import com.linnea.lingxi.vo.FollowUserVO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户关注关系数据访问入口。
 *
 * <p>单表增删改查由 {@link BaseMapper} 提供；关注/取关/列表/计数都涉及
 * ON DUPLICATE KEY、联表或键集游标，统一在 UserFollowMapper.xml 中实现。</p>
 */
public interface UserFollowMapper extends BaseMapper<UserFollow> {

    /**
     * 建立关注关系：已存在同一对关系时把状态改回已关注并刷新关注时间。
     *
     * <p>WHY 用 upsert 而不是「先查再插」：并发重复点击时查-插之间有窗口，
     * 唯一键会让第二个请求插入失败；upsert 把幂等交给数据库一次原子完成。</p>
     *
     * @param userId       关注发起方用户ID
     * @param followUserId 被关注方用户ID
     * @return 受影响行数：1-新建，2-更新既有行（MySQL 对 ON DUPLICATE UPDATE 的计数口径）
     */
    int upsertFollow(@Param("userId") Long userId, @Param("followUserId") Long followUserId);

    /**
     * 取关：把状态置为已取关。
     *
     * @param userId       关注发起方用户ID
     * @param followUserId 被关注方用户ID
     * @return 受影响行数：1-本次确实取关，0-原本就未关注
     */
    int cancelFollow(@Param("userId") Long userId, @Param("followUserId") Long followUserId);

    /**
     * 查询某人关注的人（关注列表）。
     *
     * @param userId            列表归属用户ID
     * @param cursorFollowTime  游标中的关注时间，首页传 null
     * @param cursorId          游标中的关系ID，首页传 null
     * @param limit             每页条数（已由业务层 +1 探测 hasMore）
     * @return 关注列表，按关注时间倒序
     */
    List<FollowUserVO> selectFollowingList(@Param("userId") Long userId,
                                           @Param("cursorFollowTime") LocalDateTime cursorFollowTime,
                                           @Param("cursorId") Long cursorId,
                                           @Param("limit") int limit);

    /**
     * 查询某人的粉丝（粉丝列表）。
     *
     * @param followUserId      被关注的用户ID
     * @param cursorFollowTime  游标中的关注时间，首页传 null
     * @param cursorId          游标中的关系ID，首页传 null
     * @param limit             每页条数（已由业务层 +1 探测 hasMore）
     * @return 粉丝列表，按关注时间倒序
     */
    List<FollowUserVO> selectFollowerList(@Param("followUserId") Long followUserId,
                                          @Param("cursorFollowTime") LocalDateTime cursorFollowTime,
                                          @Param("cursorId") Long cursorId,
                                          @Param("limit") int limit);

    /**
     * 批量查询「当前用户已关注了目标列表里的哪些人」。
     *
     * <p>WHY 批量而不逐个查：一页最多 30 条，逐条查就是 N+1；这里一次 IN 查询，
     * 由业务层映射成 isFollowing 标记。</p>
     *
     * @param userId            当前登录用户ID
     * @param targetUserIdList  本页待判断的目标用户ID列表，不可为空且长度受限（≤30）
     * @return 已关注的目标用户ID列表
     */
    List<Long> selectFollowingIdList(@Param("userId") Long userId,
                                     @Param("targetUserIdList") List<Long> targetUserIdList);

    /**
     * 统计某人关注了多少人。
     *
     * @param userId 用户ID
     * @return 已关注人数
     */
    long countFollowing(@Param("userId") Long userId);

    /**
     * 统计某人有多少粉丝。
     *
     * @param followUserId 被关注的用户ID
     * @return 粉丝数
     */
    long countFollower(@Param("followUserId") Long followUserId);
}
