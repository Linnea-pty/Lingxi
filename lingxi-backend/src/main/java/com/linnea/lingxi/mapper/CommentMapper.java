package com.linnea.lingxi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linnea.lingxi.entity.Comment;
import com.linnea.lingxi.vo.CommentReplyCountVO;
import com.linnea.lingxi.vo.CommentVO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评论表数据访问入口。
 *
 * <p>评论列表需要联表取作者与被回复人昵称、并按 (create_time, id) 键集分页，
 * 属复杂查询，实现在 resources/mapper/CommentMapper.xml。</p>
 *
 * <p>注意：自定义 XML 不会带上 {@code @TableLogic} 的自动过滤条件，因此 XML 内必须显式写 is_deleted = 0。</p>
 */
public interface CommentMapper extends BaseMapper<Comment> {

    /**
     * 查询某个动态下的一级评论（parent_id = 0），按时间倒序，最新在前。
     *
     * @param postId           动态ID
     * @param cursorCreateTime 游标创建时间，首页传 null
     * @param cursorId         游标评论ID，首页传 null
     * @param limit            取行数，调用方传「每页条数 + 1」用于探测下一页
     * @return 一级评论列表，无数据时为空集合
     */
    List<CommentVO> selectRootCommentList(@Param("postId") Long postId,
                                          @Param("cursorCreateTime") LocalDateTime cursorCreateTime,
                                          @Param("cursorId") Long cursorId,
                                          @Param("limit") int limit);

    /**
     * 查询某个讨论串下的全部回复（root_id = 根评论ID 且 parent_id &lt;&gt; 0），按时间正序，符合阅读顺序。
     *
     * @param rootCommentId    讨论串根评论ID
     * @param cursorCreateTime 游标创建时间，首页传 null
     * @param cursorId         游标评论ID，首页传 null
     * @param limit            取行数，调用方传「每页条数 + 1」用于探测下一页
     * @return 回复列表，无数据时为空集合
     */
    List<CommentVO> selectReplyList(@Param("rootCommentId") Long rootCommentId,
                                    @Param("cursorCreateTime") LocalDateTime cursorCreateTime,
                                    @Param("cursorId") Long cursorId,
                                    @Param("limit") int limit);

    /**
     * 批量统计若干根评论各自的回复条数，用于避免逐条查询的 N+1。
     *
     * @param rootIdList 根评论ID集合，调用方需保证非空
     * @return 每个根评论一行，无回复的根评论不出现在结果中
     */
    List<CommentReplyCountVO> selectReplyCountList(@Param("rootIdList") List<Long> rootIdList);

    /**
     * 查询某个用户发表的评论（含其回复），按时间倒序，且过滤掉所属动态已删除的记录。
     *
     * <p>「我的评论」列表里的评论如果所属动态已被删除，点进去必然是 POST_NOT_FOUND，
     * 因此在 SQL 层 JOIN post 过滤，而不是返回给前端再补一次判定。</p>
     *
     * @param userId           评论人ID
     * @param cursorCreateTime 游标创建时间，首页传 null
     * @param cursorId         游标评论ID，首页传 null
     * @param limit            取行数，调用方传「每页条数 + 1」用于探测下一页
     * @return 评论列表，无数据时为空集合
     */
    List<CommentVO> selectUserCommentList(@Param("userId") Long userId,
                                          @Param("cursorCreateTime") LocalDateTime cursorCreateTime,
                                          @Param("cursorId") Long cursorId,
                                          @Param("limit") int limit);

    /**
     * 查询一条评论及其全部后代的ID（含自身），用于级联删除。
     *
     * <p>WHY 用递归 CTE：评论是平铺存储（parent_id 指向父评论、root_id 指向讨论串根），
     * 删除一条评论时，挂在它下面的回复会变成孤儿——一级评论列表按 parent_id = 0 查不到它们，
     * 回复列表却仍会返回。递归把整棵子树一次取全，避免分层循环查询。
     * 只返回未删除的行，已删除的分支不再向下展开。</p>
     *
     * @param commentId 评论ID
     * @return 评论自身与全部后代ID；评论不存在或已删除时为空集合
     */
    List<Long> selectDescendantIdList(@Param("commentId") Long commentId);

    /**
     * 按ID批量逻辑删除评论。
     *
     * <p>自定义 SQL 不会被 @TableLogic 改写，is_deleted=1 与 update_time 必须手写。
     * ID 集合由 {@link #selectDescendantIdList(Long)} 提供，已限定非空。</p>
     *
     * @param commentIdList 待删除的评论ID集合
     * @return 实际影响行数
     */
    int softDeleteByIdList(@Param("commentIdList") List<Long> commentIdList);

    /**
     * 逻辑删除某条动态下的全部评论（删动态时级联）。
     *
     * @param postId 动态ID
     * @return 实际影响行数
     */
    int softDeleteByPostId(@Param("postId") Long postId);
}
