package com.linnea.lingxi.service;

import com.linnea.lingxi.dto.CommentQueryDTO;
import com.linnea.lingxi.dto.CreateCommentDTO;
import com.linnea.lingxi.dto.ListQueryDTO;
import com.linnea.lingxi.vo.CommentPageVO;
import com.linnea.lingxi.vo.CommentVO;

/**
 * 评论业务服务：发表评论与两级列表（一级评论、讨论串回复）。
 *
 * <p>讨论串模型：一级评论的 root_id 等于自身ID，回复的 root_id 继承父评论的 root_id、
 * reply_user_id 记为被回复人。列表按 root_id 聚合，一级评论与回复用 parent_id 区分，两者互补不重不漏。</p>
 */
public interface CommentService {

    /**
     * 对指定动态发表评论或回复。
     *
     * @param createCommentInfo 评论请求参数（正文 + 可选父评论ID + 可选幂等键）
     * @param postId            动态ID，来自路径参数
     * @param userId            评论人ID，来自认证上下文
     * @return 新建评论的对外信息；带 requestId 重复提交时返回首次发表的同一条评论
     */
    CommentVO createComment(CreateCommentDTO createCommentInfo, Long postId, Long userId);

    /**
     * 查询某个动态下的一级评论分页，按时间倒序，并附带各讨论串的回复数。
     *
     * @param postId         动态ID
     * @param commentQueryInfo 分页参数（游标 + 每页条数）
     * @return 一级评论分页结果
     */
    CommentPageVO queryRootCommentPage(Long postId, CommentQueryDTO commentQueryInfo);

    /**
     * 查询某个讨论串下的回复分页，按时间正序。
     *
     * @param rootCommentId  讨论串根评论ID
     * @param commentQueryInfo 分页参数（游标 + 每页条数）
     * @return 回复分页结果
     */
    CommentPageVO queryReplyPage(Long rootCommentId, CommentQueryDTO commentQueryInfo);

    /**
     * 删除一条评论（逻辑删除），连同其下全部回复一起删除。
     *
     * <p>仅作者可删。级联范围是「该评论及其所有后代」：只删自身会让回复变成孤儿——
     * 一级评论列表按 parent_id=0 查不到它们，回复列表却仍返回，形成「串头消失、串里还有回复」的脏数据。</p>
     *
     * @param commentId      评论ID
     * @param operatorUserId 当前登录用户ID
     */
    void deleteComment(Long commentId, Long operatorUserId);

    /**
     * 管理员删除任意评论（逻辑删除），连同其下全部回复一起删除。
     *
     * <p>与 {@link #deleteComment(Long, Long)} 的唯一区别是不校验作者归属；
     * 级联范围保持「该评论及其全部后代」，与作者自删一致——只删自身会留下孤儿回复。</p>
     *
     * @param commentId      评论ID
     * @param operatorUserId 管理员ID，仅用于日志追溯
     */
    void removeCommentByAdmin(Long commentId, Long operatorUserId);

    /**
     * 分页查询某个用户发表的评论（含其回复），按时间倒序，过滤所属动态已删除的记录。
     *
     * @param userId        目标用户ID（当前只允许查询本人）
     * @param listQueryInfo 分页参数
     * @return 评论分页结果
     */
    CommentPageVO queryMyCommentPage(Long userId, ListQueryDTO listQueryInfo);
}
