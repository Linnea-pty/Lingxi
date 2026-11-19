package com.linnea.lingxi.vo;

import com.linnea.lingxi.entity.Post;
import com.linnea.lingxi.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 动态对外信息（View Object）。
 *
 * <p>发布结果与信息流列表共用本结构：作者信息在此扁平展开（authorId / authorNickname / authorAvatarUrl），
 * 避免多一层嵌套对象，也便于 XML 单条 SQL 直接映射。
 * 刻意不含 isDeleted：逻辑删除状态不外泄，前端看到的必然是可展示数据。</p>
 */
@Data
@Schema(description = "动态对外信息")
public class PostVO {

    /** 作者已注销时的占位昵称：动态保留展示，但不再暴露原账号资料。列表路径也复用它做兜底。 */
    public static final String DELETED_AUTHOR_NICKNAME = "已注销用户";

    /** 置顶标记：1-置顶，与 DDL 中 is_pinned 取值一致。 */
    private static final int PINNED_YES = 1;

    /** 动态ID。 */
    @Schema(description = "动态ID", example = "1")
    private Long postId;

    /** 动态正文。 */
    @Schema(description = "动态正文", example = "今天把信息流的分页改成了键集分页")
    private String content;

    /** 发布来源：web-网页端发布，agent-AI 助手代发。 */
    @Schema(description = "发布来源：web-网页端发布，agent-AI 助手代发", example = "web")
    private String source;

    /** 是否置顶：Agent 代发的动态为 true，网页端发布为 false。 */
    @Schema(description = "是否置顶", example = "false")
    private Boolean isPinned;

    /** 创建时间，同时作为键集分页排序键之一。 */
    @Schema(description = "创建时间", example = "2026-11-17T03:40:00")
    private LocalDateTime createTime;

    /** 作者用户ID。 */
    @Schema(description = "作者用户ID", example = "1")
    private Long authorId;

    /** 作者昵称；作者已注销时为「已注销用户」。 */
    @Schema(description = "作者昵称，作者已注销时为「已注销用户」", example = "灵犀小明")
    private String authorNickname;

    /** 作者头像地址；未设置或作者已注销时为空串。 */
    @Schema(description = "作者头像地址，未设置或作者已注销时为空串")
    private String authorAvatarUrl;

    /** 点赞数：信息流列表实时统计填充；发布与幂等返回固定 0（发布那一刻必然没有互动）。 */
    @Schema(description = "点赞数，发布接口固定返回 0", example = "8")
    private Long likeCount;

    /** 评论数（含全部回复层级）：信息流列表实时统计填充；发布与幂等返回固定 0。 */
    @Schema(description = "评论数（含回复），发布接口固定返回 0", example = "3")
    private Long commentCount;

    /** 点赞时间：仅「我赞过的动态」列表填充，作为该列表的键集游标第一列；其他接口为 null。 */
    @Schema(description = "点赞时间，仅「我赞过的动态」列表返回，其他接口为 null", example = "2026-11-17T03:40:00")
    private LocalDateTime likedAt;

    /**
     * 由动态实体与作者实体组装对外对象。
     *
     * @param post   动态实体，不可为空
     * @param author 作者实体，可为 null（作者已注销时）
     * @return 动态对外信息
     */
    public static PostVO from(Post post, User author) {
        PostVO postVO = new PostVO();
        // 步骤1：拷贝动态自身字段，库中 0/1 的置顶标记转成布尔，避免前端判数值
        postVO.setPostId(post.getId());
        postVO.setContent(post.getContent());
        postVO.setSource(post.getSource());
        postVO.setIsPinned(post.getIsPinned() != null && post.getIsPinned() == PINNED_YES);
        postVO.setCreateTime(post.getCreateTime());
        // 步骤2：作者信息兜底，注销用户保留作者ID便于追溯，昵称与头像给占位值
        postVO.setAuthorId(post.getUserId());
        postVO.setAuthorNickname(author == null ? DELETED_AUTHOR_NICKNAME : author.getNickname());
        postVO.setAuthorAvatarUrl(author == null || author.getAvatarUrl() == null ? "" : author.getAvatarUrl());
        // 步骤3：刚发布的动态必然没有互动，计数固定 0；信息流列表由服务层实时统计后覆盖
        postVO.setLikeCount(0L);
        postVO.setCommentCount(0L);
        return postVO;
    }
}
