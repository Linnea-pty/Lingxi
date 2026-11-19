package com.linnea.lingxi.service.impl;

import com.linnea.lingxi.common.exception.BusinessException;
import com.linnea.lingxi.common.exception.ErrorCode;
import com.linnea.lingxi.dto.CommentQueryDTO;
import com.linnea.lingxi.dto.CreateCommentDTO;
import com.linnea.lingxi.dto.CreatePostDTO;
import com.linnea.lingxi.dto.ListQueryDTO;
import com.linnea.lingxi.dto.RegisterDTO;
import com.linnea.lingxi.dto.UpdatePostDTO;
import com.linnea.lingxi.mapper.CommentMapper;
import com.linnea.lingxi.service.AuthService;
import com.linnea.lingxi.service.CommentService;
import com.linnea.lingxi.service.PostLikeService;
import com.linnea.lingxi.service.PostService;
import com.linnea.lingxi.support.AbstractIntegrationTest;
import com.linnea.lingxi.vo.CommentPageVO;
import com.linnea.lingxi.vo.CommentVO;
import com.linnea.lingxi.vo.LikeUserPageVO;
import com.linnea.lingxi.vo.LoginVO;
import com.linnea.lingxi.vo.PostPageVO;
import com.linnea.lingxi.vo.PostVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 动态与评论增删改、点赞/评论列表的集成测试，跑在真实 MySQL 与真实 Redis 上。
 *
 * <p>WHY 放集成层而不是纯单测：本次新增逻辑的正确性依赖三处数据库方言行为——
 * 逻辑删除后 {@code selectById} 查不到、递归 CTE 能取全评论子树、
 * 键集游标在「同一秒多条记录」时仍稳定翻页。这些用 Mockito 替身验证不了，
 * 替身只能证明「方法被调用了」，证明不了「删完之后列表真的干净」。</p>
 */
@Transactional
class PostModerationIT extends AbstractIntegrationTest {

    /** 测试账号密码，满足 8~64 位要求。 */
    private static final String PASSWORD = "lingxi@2026";

    /** 用户名序号：同一测试内多次注册需要不同用户名，避免撞 uk_username。 */
    private static final AtomicInteger USERNAME_SEQ = new AtomicInteger();

    /** 认证业务服务：注册即登录，用于拿用户ID。 */
    @Autowired
    private AuthService authService;

    /** 动态业务服务。 */
    @Autowired
    private PostService postService;

    /** 评论业务服务。 */
    @Autowired
    private CommentService commentService;

    /** 点赞业务服务。 */
    @Autowired
    private PostLikeService postLikeService;

    /** 评论数据访问：直接按主键回查，验证级联删除确实把行标记为已删（selectById 会自动过滤逻辑删除）。 */
    @Autowired
    private CommentMapper commentMapper;

    @Test
    @DisplayName("修改动态：作者可改正文，返回最新内容且不带点赞时间")
    void updatePost_byAuthor_updatesContent() {
        // 前置：注册作者并发一条动态
        Long authorId = registerUser();
        PostVO publishedPost = publishPost(authorId, "修改前的正文");

        // 步骤1：作者修改正文
        UpdatePostDTO updatePostInfo = new UpdatePostDTO();
        updatePostInfo.setContent("  修改后的正文  ");
        PostVO updatedPost = postService.updatePost(publishedPost.getPostId(), updatePostInfo, authorId);

        // 步骤2：断言正文已 trim 落库，且该接口不返回点赞时间字段
        assertThat(updatedPost.getContent()).isEqualTo("修改后的正文");
        assertThat(updatedPost.getLikedAt()).isNull();
        assertThat(updatedPost.getLikeCount()).isNotNull();
    }

    @Test
    @DisplayName("修改动态：非作者被拒，返回 COMMON_FORBIDDEN 且原文案不变")
    void updatePost_byOtherUser_isForbidden() {
        // 前置：作者发动态，另一个用户尝试改
        Long authorId = registerUser();
        Long strangerId = registerUser();
        PostVO publishedPost = publishPost(authorId, "别人的动态");

        UpdatePostDTO updatePostInfo = new UpdatePostDTO();
        updatePostInfo.setContent("篡改内容");

        // 执行 + 断言：越权被拒
        assertThatThrownBy(() -> postService.updatePost(publishedPost.getPostId(), updatePostInfo, strangerId))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.COMMON_FORBIDDEN.getCode());

        // 断言：原内容未被改动
        PostVO reloadedPost = postService.updatePost(publishedPost.getPostId(),
                buildUpdateInfo("别人的动态"), authorId);
        assertThat(reloadedPost.getContent()).isEqualTo("别人的动态");
    }

    @Test
    @DisplayName("修改动态：动态不存在返回 POST_NOT_FOUND")
    void updatePost_whenMissing_throwsNotFound() {
        // 前置：注册用户但不发动态
        Long operatorUserId = registerUser();

        // 执行 + 断言
        assertThatThrownBy(() -> postService.updatePost(999_999_999L, buildUpdateInfo("随便改"), operatorUserId))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.POST_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("删除动态：作者可删，动态与评论一起消失，重复删除返回 POST_NOT_FOUND")
    void deletePost_cascadesComments() {
        // 前置：作者发动态并评论
        Long authorId = registerUser();
        PostVO publishedPost = publishPost(authorId, "待删除的动态");
        CommentVO cascadedComment = commentService.createComment(buildCommentInfo("一条会被级联删除的评论", 0L),
                publishedPost.getPostId(), authorId);

        // 步骤1：作者删除动态
        postService.deletePost(publishedPost.getPostId(), authorId);

        // 步骤2：动态已不可改（逻辑删除后按不存在处理）
        assertThatThrownBy(() -> postService.updatePost(publishedPost.getPostId(),
                buildUpdateInfo("改不了"), authorId))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.POST_NOT_FOUND.getCode());

        // 步骤3：评论行已被级联逻辑删除（selectById 自动过滤逻辑删除，查不到即已删）
        assertThat(commentMapper.selectById(cascadedComment.getCommentId())).isNull();

        // 步骤4：评论列表接口按「动态不存在」拒绝，不会返回孤儿评论
        assertThatThrownBy(() -> queryRootComments(publishedPost.getPostId()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.POST_NOT_FOUND.getCode());

        // 步骤5：重复删除被拒，体现「已删即不存在」
        assertThatThrownBy(() -> postService.deletePost(publishedPost.getPostId(), authorId))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.POST_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("删除动态：非作者被拒")
    void deletePost_byOtherUser_isForbidden() {
        // 前置
        Long authorId = registerUser();
        Long strangerId = registerUser();
        PostVO publishedPost = publishPost(authorId, "不该被别人删");

        // 执行 + 断言
        assertThatThrownBy(() -> postService.deletePost(publishedPost.getPostId(), strangerId))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.COMMON_FORBIDDEN.getCode());
    }

    @Test
    @DisplayName("删除评论：删一级评论时其下回复一并消失，讨论串不再返回")
    void deleteComment_removesDescendantReplies() {
        // 前置：作者发动态，甲发一级评论，乙回复甲
        Long authorId = registerUser();
        Long replierId = registerUser();
        PostVO publishedPost = publishPost(authorId, "讨论串测试");
        CommentVO rootComment = commentService.createComment(buildCommentInfo("一级评论", 0L),
                publishedPost.getPostId(), authorId);
        CommentVO replyComment = commentService.createComment(buildCommentInfo("回复一级评论", rootComment.getCommentId()),
                publishedPost.getPostId(), replierId);

        // 前置校验：回复确实存在
        assertThat(queryReplies(rootComment.getCommentId()).getCommentList()).hasSize(1);

        // 步骤1：一级评论作者删除该评论
        commentService.deleteComment(rootComment.getCommentId(), authorId);

        // 步骤2：根评论与其回复都被逻辑删除（selectById 过滤逻辑删除，查不到即已删）
        assertThat(commentMapper.selectById(rootComment.getCommentId())).isNull();
        assertThat(commentMapper.selectById(replyComment.getCommentId())).isNull();

        // 步骤3：讨论串接口按「根评论不存在」拒绝，不会返回孤儿回复
        assertThatThrownBy(() -> queryReplies(rootComment.getCommentId()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.COMMENT_NOT_FOUND.getCode());

        // 步骤4：动态下的一级评论列表为空
        assertThat(queryRootComments(publishedPost.getPostId()).getCommentList()).isEmpty();
    }

    @Test
    @DisplayName("删除评论：非作者被拒")
    void deleteComment_byOtherUser_isForbidden() {
        // 前置
        Long authorId = registerUser();
        Long strangerId = registerUser();
        PostVO publishedPost = publishPost(authorId, "评论归属测试");
        CommentVO rootComment = commentService.createComment(buildCommentInfo("我的评论", 0L),
                publishedPost.getPostId(), authorId);

        // 执行 + 断言
        assertThatThrownBy(() -> commentService.deleteComment(rootComment.getCommentId(), strangerId))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.COMMON_FORBIDDEN.getCode());
    }

    @Test
    @DisplayName("点赞人列表：按点赞时间倒序，size=1 时能靠 nextCursor 翻完两页")
    void queryLikeUserPage_paginatesByCursor() {
        // 前置：作者发动态，两个不同用户点赞
        Long authorId = registerUser();
        PostVO publishedPost = publishPost(authorId, "点赞列表测试");
        Long firstLikerId = registerUser();
        Long secondLikerId = registerUser();
        postLikeService.likePost(publishedPost.getPostId(), firstLikerId);
        postLikeService.likePost(publishedPost.getPostId(), secondLikerId);

        // 步骤1：第一页取 1 条，必须还有下一页且游标非空
        LikeUserPageVO firstPage = postLikeService.queryLikeUserPage(publishedPost.getPostId(),
                buildListQueryInfo(null, 1));
        assertThat(firstPage.getUserList()).hasSize(1);
        assertThat(firstPage.getHasMore()).isTrue();
        assertThat(firstPage.getNextCursor()).isNotBlank();

        // 步骤2：带游标取第二页，拿到剩下 1 条且没有更多
        LikeUserPageVO secondPage = postLikeService.queryLikeUserPage(publishedPost.getPostId(),
                buildListQueryInfo(firstPage.getNextCursor(), 1));
        assertThat(secondPage.getUserList()).hasSize(1);
        assertThat(secondPage.getHasMore()).isFalse();
        assertThat(secondPage.getNextCursor()).isNull();

        // 步骤3：两页合起来正好是两位点赞人，不重不漏
        assertThat(firstPage.getUserList().get(0).getUserId())
                .isNotEqualTo(secondPage.getUserList().get(0).getUserId());
    }

    @Test
    @DisplayName("我赞过的动态：含点赞时间，动态删除后从列表消失但点赞记录仍保留")
    void queryMyLikedPostPage_hidesDeletedPosts() {
        // 前置：作者发两条动态，点赞人各点一条
        Long authorId = registerUser();
        Long likerId = registerUser();
        PostVO firstPost = publishPost(authorId, "我点过赞的第一条");
        PostVO secondPost = publishPost(authorId, "我点过赞的第二条");
        postLikeService.likePost(firstPost.getPostId(), likerId);
        postLikeService.likePost(secondPost.getPostId(), likerId);

        // 步骤1：列表两条，且每条都带点赞时间（游标依赖该字段，不可为 null）
        PostPageVO likedPostPage = postService.queryMyLikedPostPage(likerId, buildListQueryInfo(null, 10));
        assertThat(likedPostPage.getPostList()).hasSize(2);
        assertThat(likedPostPage.getPostList()).allSatisfy(postInfo -> assertThat(postInfo.getLikedAt()).isNotNull());

        // 步骤2：作者删除其中一条后，点赞人列表只剩一条
        postService.deletePost(secondPost.getPostId(), authorId);
        PostPageVO reloadedPage = postService.queryMyLikedPostPage(likerId, buildListQueryInfo(null, 10));
        assertThat(reloadedPage.getPostList()).hasSize(1);
        assertThat(reloadedPage.getPostList().get(0).getPostId()).isEqualTo(firstPost.getPostId());
    }

    @Test
    @DisplayName("我的评论：返回本人的评论，所属动态被删除后不再出现")
    void queryMyCommentPage_filtersDeletedPosts() {
        // 前置：作者发两条动态，评论人各评一条
        Long authorId = registerUser();
        Long commenterId = registerUser();
        PostVO keptPost = publishPost(authorId, "保留的动态");
        PostVO removedPost = publishPost(authorId, "会被删除的动态");
        commentService.createComment(buildCommentInfo("留在列表里的评论", 0L), keptPost.getPostId(), commenterId);
        commentService.createComment(buildCommentInfo("会随动态消失的评论", 0L), removedPost.getPostId(), commenterId);

        // 步骤1：两条评论都在
        CommentPageVO myCommentPage = commentService.queryMyCommentPage(commenterId, buildListQueryInfo(null, 10));
        assertThat(myCommentPage.getCommentList()).hasSize(2);

        // 步骤2：作者删除其中一条动态后，只剩一条
        postService.deletePost(removedPost.getPostId(), authorId);
        CommentPageVO reloadedPage = commentService.queryMyCommentPage(commenterId, buildListQueryInfo(null, 10));
        assertThat(reloadedPage.getCommentList()).hasSize(1);
        assertThat(reloadedPage.getCommentList().get(0).getContent()).isEqualTo("留在列表里的评论");
    }

    /**
     * 注册一个随机用户名的新用户。
     *
     * @return 新用户ID
     */
    private Long registerUser() {
        // 步骤1：用户名加自增序号，保证同一测试内多次注册不撞唯一键
        String username = "mod_" + USERNAME_SEQ.incrementAndGet() + "_" + System.nanoTime() % 100_000L;
        RegisterDTO registerInfo = new RegisterDTO();
        registerInfo.setUsername(username);
        registerInfo.setPassword(PASSWORD);
        registerInfo.setNickname(username);
        // 步骤2：注册即登录，直接取用户ID
        LoginVO loginResult = authService.register(registerInfo);
        return loginResult.getUserInfo().getUserId();
    }

    /**
     * 以指定用户身份发布一条动态。
     *
     * @param userId  作者ID
     * @param content 正文
     * @return 发布后的动态
     */
    private PostVO publishPost(Long userId, String content) {
        // 步骤1：构造请求参数（不传幂等键，每次都是一条新动态）
        CreatePostDTO createPostInfo = new CreatePostDTO();
        createPostInfo.setContent(content);
        // 步骤2：按网页端身份发布（agent=false，不置顶）
        return postService.createPost(createPostInfo, userId, false);
    }

    /**
     * 构造评论请求参数。
     *
     * @param content  正文
     * @param parentId 父评论ID，0 表示一级评论
     * @return 评论请求参数
     */
    private CreateCommentDTO buildCommentInfo(String content, Long parentId) {
        // 步骤1：写入正文与父评论ID
        CreateCommentDTO createCommentInfo = new CreateCommentDTO();
        createCommentInfo.setContent(content);
        createCommentInfo.setParentId(parentId);
        return createCommentInfo;
    }

    /**
     * 构造修改动态请求参数。
     *
     * @param content 新正文
     * @return 修改请求参数
     */
    private UpdatePostDTO buildUpdateInfo(String content) {
        // 步骤1：写入正文
        UpdatePostDTO updatePostInfo = new UpdatePostDTO();
        updatePostInfo.setContent(content);
        return updatePostInfo;
    }

    /**
     * 构造列表分页参数。
     *
     * @param cursor 游标，首页传 null
     * @param size   每页条数
     * @return 分页参数
     */
    private ListQueryDTO buildListQueryInfo(String cursor, int size) {
        // 步骤1：写入游标与条数
        ListQueryDTO listQueryInfo = new ListQueryDTO();
        listQueryInfo.setCursor(cursor);
        listQueryInfo.setSize(size);
        return listQueryInfo;
    }

    /**
     * 查询某动态下的一级评论。
     *
     * @param postId 动态ID
     * @return 一级评论分页结果
     */
    private CommentPageVO queryRootComments(Long postId) {
        // 步骤1：构造分页参数（10 条足够断言）
        CommentQueryDTO commentQueryInfo = new CommentQueryDTO();
        commentQueryInfo.setSize(10);
        return commentService.queryRootCommentPage(postId, commentQueryInfo);
    }

    /**
     * 查询某讨论串下的回复。
     *
     * @param rootCommentId 根评论ID
     * @return 回复分页结果
     */
    private CommentPageVO queryReplies(Long rootCommentId) {
        // 步骤1：构造分页参数（10 条足够断言）
        CommentQueryDTO commentQueryInfo = new CommentQueryDTO();
        commentQueryInfo.setSize(10);
        return commentService.queryReplyPage(rootCommentId, commentQueryInfo);
    }
}
