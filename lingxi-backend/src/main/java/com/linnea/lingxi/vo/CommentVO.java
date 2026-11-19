package com.linnea.lingxi.vo;

import com.linnea.lingxi.entity.Comment;
import com.linnea.lingxi.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评论对外信息（View Object）。
 *
 * <p>作者与被回复人信息在此扁平展开，避免每条评论再多一层嵌套对象；
 * 一级评论额外携带 replyCount（该讨论串下的回复条数），回复列表项不填该字段。</p>
 */
@Data
@Schema(description = "评论对外信息")
public class CommentVO {

    /** 作者已注销时的占位昵称：评论保留展示，但不再暴露原账号资料。列表路径也复用它做兜底。 */
    public static final String DELETED_AUTHOR_NICKNAME = "已注销用户";

    /** 未指定被回复人时的空值（一级评论无被回复人）。 */
    private static final long NO_REPLY_USER_ID = 0L;

    /** 评论ID。 */
    @Schema(description = "评论ID", example = "1")
    private Long commentId;

    /** 所属动态ID。 */
    @Schema(description = "所属动态ID", example = "1")
    private Long postId;

    /** 父评论ID：0 表示一级评论，大于 0 表示对某条评论的回复。 */
    @Schema(description = "父评论ID，0 表示一级评论", example = "0")
    private Long parentId;

    /** 讨论串根评论ID：一级评论等于自身ID，回复继承父评论的根ID。 */
    @Schema(description = "讨论串根评论ID", example = "1")
    private Long rootId;

    /** 评论正文。 */
    @Schema(description = "评论正文", example = "这段注释写得很清楚")
    private String content;

    /** 创建时间。 */
    @Schema(description = "创建时间", example = "2026-11-17T03:40:00")
    private LocalDateTime createTime;

    /** 作者用户ID。 */
    @Schema(description = "作者用户ID", example = "2")
    private Long authorId;

    /** 作者昵称；作者已注销时为「已注销用户」。 */
    @Schema(description = "作者昵称，作者已注销时为「已注销用户」", example = "灵犀小明")
    private String authorNickname;

    /** 作者头像地址；未设置或作者已注销时为空串。 */
    @Schema(description = "作者头像地址，未设置或作者已注销时为空串")
    private String authorAvatarUrl;

    /** 被回复人ID：一级评论为 0。 */
    @Schema(description = "被回复人ID，一级评论为 0", example = "0")
    private Long replyUserId;

    /** 被回复人昵称：一级评论为空串。 */
    @Schema(description = "被回复人昵称，一级评论为空串")
    private String replyUserNickname;

    /** 该讨论串下的回复条数：只在一级评论列表填充，回复列表为 null。 */
    @Schema(description = "该讨论串下的回复条数，仅一级评论列表填充", example = "3")
    private Long replyCount;

    /**
     * 由评论实体、作者实体与被回复人实体组装对外对象。
     *
     * @param comment   评论实体，不可为空
     * @param author    作者实体，可为 null（作者已注销时）
     * @param replyUser 被回复人实体，可为 null（一级评论或已被注销时）
     * @return 评论对外信息
     */
    public static CommentVO from(Comment comment, User author, User replyUser) {
        CommentVO commentVO = new CommentVO();
        // 步骤1：拷贝评论自身字段
        commentVO.setCommentId(comment.getId());
        commentVO.setPostId(comment.getPostId());
        commentVO.setParentId(comment.getParentId());
        commentVO.setRootId(comment.getRootId());
        commentVO.setContent(comment.getContent());
        commentVO.setCreateTime(comment.getCreateTime());
        // 步骤2：作者信息兜底，注销用户保留作者ID便于追溯，昵称与头像给占位值
        commentVO.setAuthorId(comment.getUserId());
        commentVO.setAuthorNickname(author == null ? DELETED_AUTHOR_NICKNAME : author.getNickname());
        commentVO.setAuthorAvatarUrl(author == null || author.getAvatarUrl() == null ? "" : author.getAvatarUrl());
        // 步骤3：被回复人兜底，一级评论 reply_user_id 为 0，昵称给空串而不是占位昵称
        long replyUserId = comment.getReplyUserId() == null ? NO_REPLY_USER_ID : comment.getReplyUserId();
        commentVO.setReplyUserId(replyUserId);
        if (replyUserId == NO_REPLY_USER_ID) {
            commentVO.setReplyUserNickname("");
        } else {
            commentVO.setReplyUserNickname(replyUser == null ? DELETED_AUTHOR_NICKNAME : replyUser.getNickname());
        }
        return commentVO;
    }
}
