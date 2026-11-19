package com.linnea.lingxi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linnea.lingxi.entity.Post;
import com.linnea.lingxi.vo.PostInteractionCountVO;
import com.linnea.lingxi.vo.PostVO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 动态表数据访问入口。
 *
 * <p>单表增删改查由 {@link BaseMapper} 提供；信息流查询需要联表取作者资料并按
 * (is_pinned, create_time, id) 组合游标过滤，属复杂查询，实现在 resources/mapper/PostMapper.xml。</p>
 *
 * <p>注意：自定义 XML 不会带上 {@code @TableLogic} 的自动过滤条件，
 * 因此 XML 内必须显式写 is_deleted = 0。</p>
 */
public interface PostMapper extends BaseMapper<Post> {

    /**
     * 查询信息流一页数据，按 置顶 → 创建时间 → ID 倒序，与索引 idx_feed_cursor 顺序一致。
     *
     * @param cursorPinned     游标中的置顶标记，首页传 null
     * @param cursorCreateTime 游标中的创建时间，首页传 null
     * @param cursorId         游标中的动态ID，首页传 null
     * @param limit            本次取的行数，调用方传「每页条数 + 1」用于探测是否还有下一页
     * @return 动态列表（含作者昵称与头像），无数据时为空集合
     */
    List<PostVO> selectFeedList(@Param("cursorPinned") Integer cursorPinned,
                                @Param("cursorCreateTime") LocalDateTime cursorCreateTime,
                                @Param("cursorId") Long cursorId,
                                @Param("limit") int limit);

    /**
     * 批量查询当前页动态的点赞数与评论数。
     *
     * <p>一次 IN + 两个标量子查询取回，避免逐条统计造成 N+1，也避免 JOIN 聚合把全表分组物化。</p>
     *
     * @param postIdList 动态ID集合，调用方需保证非空（XML 中展开为 IN 列表）
     * @return 每个动态一行，无互动的动态计数为 0
     */
    List<PostInteractionCountVO> selectPostCountList(@Param("postIdList") List<Long> postIdList);

    /**
     * 查询某个用户点赞过的动态（仅有效点赞，按点赞时间倒序）。
     *
     * <p>为什么从 post_like 出发而不是从 post 出发：列表语义是「我赞过的」，
     * 排序键是点赞时间而非动态发布时间；从 post_like 驱动才能让
     * idx_user_active_time(user_id, status, deleted_id, create_time, id) 覆盖过滤与排序。</p>
     *
     * @param userId           点赞用户ID
     * @param cursorCreateTime 游标中的点赞时间，首页传 null
     * @param cursorPostId     游标中的动态ID（同一用户对同一动态仅一条有效点赞，可作排序第二列），首页传 null
     * @param limit            取行数，调用方传「每页条数 + 1」用于探测下一页
     * @return 动态列表（含作者昵称与头像，不含互动计数，由服务层批量补齐）
     */
    List<PostVO> selectLikedPostList(@Param("userId") Long userId,
                                     @Param("cursorCreateTime") LocalDateTime cursorCreateTime,
                                     @Param("cursorPostId") Long cursorPostId,
                                     @Param("limit") int limit);
}
