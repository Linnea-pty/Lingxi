package com.linnea.lingxi.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linnea.lingxi.common.exception.BusinessException;
import com.linnea.lingxi.common.exception.ErrorCode;
import com.linnea.lingxi.common.idempotent.IdempotentGuard;
import com.linnea.lingxi.dto.CreateCommentDTO;
import com.linnea.lingxi.entity.Comment;
import com.linnea.lingxi.entity.Post;
import com.linnea.lingxi.entity.User;
import com.linnea.lingxi.mapper.CommentMapper;
import com.linnea.lingxi.mapper.PostMapper;
import com.linnea.lingxi.mapper.UserMapper;
import com.linnea.lingxi.service.NotificationService;
import com.linnea.lingxi.vo.CommentVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CommentServiceImpl} 单元测试：覆盖发表评论的入参校验、幂等三条路径、
 * 讨论串归属校验（root_id 继承与父评论同动态约束）。
 *
 * <p>数据访问与幂等栅栏全部用 Mockito 替身，验证的是分支逻辑而非 SQL 行为；
 * 真实唯一键与排序规则由集成测试覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
class CommentServiceImplTest {

    /** 测试用评论人ID。 */
    private static final Long AUTHOR_ID = 2001L;

    /** 测试用被回复人ID。 */
    private static final Long PARENT_AUTHOR_ID = 2002L;

    /** 测试用动态ID。 */
    private static final Long POST_ID = 8801L;

    /** 测试用另一个动态ID，用于构造「父评论属于别的动态」的场景。 */
    private static final Long OTHER_POST_ID = 8802L;

    /** 测试用父评论ID。 */
    private static final Long PARENT_COMMENT_ID = 3401L;

    /** 父评论所属讨论串根ID。 */
    private static final Long PARENT_ROOT_ID = 3400L;

    /** 测试用幂等键。 */
    private static final String REQUEST_ID = "cmt-20261117-0001";

    /** 评论幂等场景标识，与实现保持一致。 */
    private static final String SCENE_COMMENT = "comment:create";

    /** 一级评论的 parent_id 约定值。 */
    private static final long ROOT_COMMENT_PARENT_ID = 0L;

    /** 评论数据访问替身。 */
    @Mock
    private CommentMapper commentMapper;

    /** 动态数据访问替身。 */
    @Mock
    private PostMapper postMapper;

    /** 用户数据访问替身。 */
    @Mock
    private UserMapper userMapper;

    /** 幂等栅栏替身。 */
    @Mock
    private IdempotentGuard idempotentGuard;

    /**
     * 通知服务替身：本单测只验证「评论主流程不被通知逻辑阻断」，
     * 通知的类型/接收者/目标ID 由 NotificationIT 在真实库上覆盖。
     */
    @Mock
    private NotificationService notificationService;

    /** 被测对象。 */
    @InjectMocks
    private CommentServiceImpl commentService;

    /**
     * 初始化 MyBatis-Plus 的实体元数据缓存。
     *
     * <p>WHY 需要手工初始化：被测代码用 {@code LambdaUpdateWrapper.set(Comment::getRootId, ...)}
     * 做单列更新，Lambda 缓存平时由 Mapper 注册时填充；纯单元测试没有 MyBatis 容器，
     * 不预热会直接抛「can not find lambda cache for this entity」，与业务正确性无关。</p>
     */
    @BeforeAll
    static void initLambdaCache() {
        // 步骤1：用最小配置注册一次实体元数据，让 Lambda 解析到列名
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Comment.class);
    }

    @Test
    @DisplayName("缺少评论人身份：直接拒绝，避免鉴权链路被绕过时写出匿名评论")
    void createComment_missingAuthor_throwsUnauthorized() {
        // 前置
        CreateCommentDTO createCommentInfo = buildCreateCommentDTO("正常评论", null, null);

        // 执行 + 断言
        assertThatThrownBy(() -> commentService.createComment(createCommentInfo, POST_ID, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.COMMON_UNAUTHORIZED.getCode());
        verify(commentMapper, never()).insert(any(Comment.class));
    }

    @Test
    @DisplayName("正文为纯空白：转成参数错误，@NotBlank 拦不住的空格串由此兜底")
    void createComment_blankContent_throwsParamError() {
        // 前置
        CreateCommentDTO createCommentInfo = buildCreateCommentDTO("   ", null, null);

        // 执行 + 断言
        assertThatThrownBy(() -> commentService.createComment(createCommentInfo, POST_ID, AUTHOR_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.COMMON_PARAM_ERROR.getCode());
        verify(commentMapper, never()).insert(any(Comment.class));
    }

    @Test
    @DisplayName("幂等命中：返回首次那条评论，不再重复落库")
    void createComment_idempotentHit_returnsFirstResult() {
        // 前置：抢占失败，键值指向首次落库的评论ID
        CreateCommentDTO createCommentInfo = buildCreateCommentDTO("重复提交", null, REQUEST_ID);
        when(idempotentGuard.tryAcquire(SCENE_COMMENT, REQUEST_ID, AUTHOR_ID)).thenReturn(false);
        when(idempotentGuard.queryResult(SCENE_COMMENT, REQUEST_ID, AUTHOR_ID))
                .thenReturn(String.valueOf(PARENT_COMMENT_ID));
        when(idempotentGuard.isProcessing(anyString())).thenReturn(false);
        when(commentMapper.selectById(PARENT_COMMENT_ID))
                .thenReturn(buildComment(PARENT_COMMENT_ID, POST_ID, AUTHOR_ID, ROOT_COMMENT_PARENT_ID, PARENT_ROOT_ID));
        when(userMapper.selectById(AUTHOR_ID)).thenReturn(buildUser(AUTHOR_ID));

        // 执行
        CommentVO createdCommentInfo = commentService.createComment(createCommentInfo, POST_ID, AUTHOR_ID);

        // 断言：返回首次评论，且绝不插入第二条
        assertThat(createdCommentInfo.getCommentId()).isEqualTo(PARENT_COMMENT_ID);
        assertThat(createdCommentInfo.getReplyCount()).isZero();
        verify(commentMapper, never()).insert(any(Comment.class));
    }

    @Test
    @DisplayName("幂等键仍在处理中：按重复提交拒绝，不能让并发请求再落一条")
    void createComment_idempotentProcessing_throwsDuplicate() {
        // 前置：抢占失败，键值是处理中占位符
        CreateCommentDTO createCommentInfo = buildCreateCommentDTO("并发重复提交", null, REQUEST_ID);
        when(idempotentGuard.tryAcquire(SCENE_COMMENT, REQUEST_ID, AUTHOR_ID)).thenReturn(false);
        when(idempotentGuard.queryResult(SCENE_COMMENT, REQUEST_ID, AUTHOR_ID)).thenReturn("PROCESSING");
        when(idempotentGuard.isProcessing("PROCESSING")).thenReturn(true);

        // 执行 + 断言
        assertThatThrownBy(() -> commentService.createComment(createCommentInfo, POST_ID, AUTHOR_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.COMMON_DUPLICATE_REQUEST.getCode());
        verify(commentMapper, never()).insert(any(Comment.class));
    }

    @Test
    @DisplayName("动态不存在：拒绝评论，避免给已删除动态挂评论")
    void createComment_postMissing_throwsPostNotFound() {
        // 前置：抢到幂等键后业务校验失败，必须释放键以便客户端立即重试
        CreateCommentDTO createCommentInfo = buildCreateCommentDTO("正常评论", null, REQUEST_ID);
        when(idempotentGuard.tryAcquire(SCENE_COMMENT, REQUEST_ID, AUTHOR_ID)).thenReturn(true);
        when(postMapper.selectById(POST_ID)).thenReturn(null);

        // 执行 + 断言
        assertThatThrownBy(() -> commentService.createComment(createCommentInfo, POST_ID, AUTHOR_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.POST_NOT_FOUND.getCode());
        verify(idempotentGuard).release(SCENE_COMMENT, REQUEST_ID, AUTHOR_ID);
        verify(commentMapper, never()).insert(any(Comment.class));
    }

    @Test
    @DisplayName("父评论属于别的动态：拒绝写入，防止讨论串被跨动态挂载")
    void createComment_parentBelongsToOtherPost_throwsMismatch() {
        // 前置：父评论存在，但 postId 与请求不一致
        CreateCommentDTO createCommentInfo = buildCreateCommentDTO("跨动态回复", PARENT_COMMENT_ID, null);
        when(postMapper.selectById(POST_ID)).thenReturn(buildPost(POST_ID));
        when(commentMapper.selectById(PARENT_COMMENT_ID)).thenReturn(
                buildComment(PARENT_COMMENT_ID, OTHER_POST_ID, PARENT_AUTHOR_ID, ROOT_COMMENT_PARENT_ID, PARENT_ROOT_ID));

        // 执行 + 断言
        assertThatThrownBy(() -> commentService.createComment(createCommentInfo, POST_ID, AUTHOR_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.COMMENT_PARENT_MISMATCH.getCode());
        verify(commentMapper, never()).insert(any(Comment.class));
    }

    @Test
    @DisplayName("一级评论：先以 root_id=0 占位插入，再同事务回填为自身ID")
    void createComment_rootComment_backfillsRootId() {
        // 前置：正常落库并回读
        CreateCommentDTO createCommentInfo = buildCreateCommentDTO("一级评论", null, null);
        when(postMapper.selectById(POST_ID)).thenReturn(buildPost(POST_ID));
        when(commentMapper.insert(any(Comment.class))).thenAnswer(invocation -> {
            Comment newComment = invocation.getArgument(0);
            newComment.setId(PARENT_COMMENT_ID);
            return 1;
        });
        when(commentMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        Comment persistedComment = buildComment(PARENT_COMMENT_ID, POST_ID, AUTHOR_ID,
                ROOT_COMMENT_PARENT_ID, PARENT_COMMENT_ID);
        when(commentMapper.selectById(PARENT_COMMENT_ID)).thenReturn(persistedComment);
        when(userMapper.selectById(AUTHOR_ID)).thenReturn(buildUser(AUTHOR_ID));

        // 执行
        commentService.createComment(createCommentInfo, POST_ID, AUTHOR_ID);

        // 断言：插入时 root_id 为占位值 0（待回填），插入后确实发起了 root_id 回填更新
        ArgumentCaptor<Comment> insertedCommentCaptor = ArgumentCaptor.forClass(Comment.class);
        verify(commentMapper).insert(insertedCommentCaptor.capture());
        assertThat(insertedCommentCaptor.getValue().getParentId()).isEqualTo(ROOT_COMMENT_PARENT_ID);
        assertThat(insertedCommentCaptor.getValue().getRootId()).isZero();
        verify(commentMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    @DisplayName("回复评论：root_id 继承父评论、被回复人取父评论作者，不接受前端传入")
    void createComment_reply_inheritsRootIdAndReplyUser() {
        // 前置：父评论是某讨论串下的回复
        CreateCommentDTO createCommentInfo = buildCreateCommentDTO("回复评论", PARENT_COMMENT_ID, null);
        when(postMapper.selectById(POST_ID)).thenReturn(buildPost(POST_ID));
        when(commentMapper.selectById(PARENT_COMMENT_ID)).thenReturn(
                buildComment(PARENT_COMMENT_ID, POST_ID, PARENT_AUTHOR_ID, ROOT_COMMENT_PARENT_ID, PARENT_ROOT_ID));
        when(commentMapper.insert(any(Comment.class))).thenAnswer(invocation -> {
            Comment newComment = invocation.getArgument(0);
            newComment.setId(3501L);
            return 1;
        });
        when(commentMapper.selectById(3501L)).thenReturn(
                buildComment(3501L, POST_ID, AUTHOR_ID, PARENT_COMMENT_ID, PARENT_ROOT_ID));
        when(userMapper.selectById(AUTHOR_ID)).thenReturn(buildUser(AUTHOR_ID));
        when(userMapper.selectById(PARENT_AUTHOR_ID)).thenReturn(buildUser(PARENT_AUTHOR_ID));

        // 执行
        commentService.createComment(createCommentInfo, POST_ID, AUTHOR_ID);

        // 断言：root_id 继承讨论串根、reply_user_id 指向父评论作者；回复不回填 root_id
        ArgumentCaptor<Comment> insertedCommentCaptor = ArgumentCaptor.forClass(Comment.class);
        verify(commentMapper).insert(insertedCommentCaptor.capture());
        Comment insertedComment = insertedCommentCaptor.getValue();
        assertThat(insertedComment.getRootId()).isEqualTo(PARENT_ROOT_ID);
        assertThat(insertedComment.getReplyUserId()).isEqualTo(PARENT_AUTHOR_ID);
        verify(commentMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    @DisplayName("落库抛异常：释放幂等键，否则客户端在 TTL 内无法重试")
    void createComment_insertFails_releasesKey() {
        // 前置：插入时数据库异常
        CreateCommentDTO createCommentInfo = buildCreateCommentDTO("正常评论", null, REQUEST_ID);
        when(idempotentGuard.tryAcquire(SCENE_COMMENT, REQUEST_ID, AUTHOR_ID)).thenReturn(true);
        when(postMapper.selectById(POST_ID)).thenReturn(buildPost(POST_ID));
        when(commentMapper.insert(any(Comment.class))).thenThrow(new IllegalStateException("模拟数据库异常"));

        // 执行 + 断言
        assertThatThrownBy(() -> commentService.createComment(createCommentInfo, POST_ID, AUTHOR_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模拟数据库异常");
        verify(idempotentGuard).release(SCENE_COMMENT, REQUEST_ID, AUTHOR_ID);
    }

    /**
     * 构造评论请求参数。
     *
     * @param content  评论正文
     * @param parentId 父评论ID，null 表示一级评论
     * @param requestId 幂等键，null 表示不做去重
     * @return 评论请求参数
     */
    private CreateCommentDTO buildCreateCommentDTO(String content, Long parentId, String requestId) {
        CreateCommentDTO createCommentInfo = new CreateCommentDTO();
        createCommentInfo.setContent(content);
        createCommentInfo.setParentId(parentId);
        createCommentInfo.setRequestId(requestId);
        return createCommentInfo;
    }

    /**
     * 构造评论实体。
     *
     * @param commentId  评论ID
     * @param postId     所属动态ID
     * @param userId     评论人ID
     * @param parentId   父评论ID
     * @param rootId     讨论串根ID
     * @return 评论实体
     */
    private Comment buildComment(Long commentId, Long postId, Long userId, Long parentId, Long rootId) {
        Comment comment = new Comment();
        comment.setId(commentId);
        comment.setPostId(postId);
        comment.setUserId(userId);
        comment.setParentId(parentId);
        comment.setRootId(rootId);
        comment.setReplyUserId(parentId == ROOT_COMMENT_PARENT_ID ? 0L : PARENT_AUTHOR_ID);
        comment.setContent("评论正文");
        comment.setCreateTime(LocalDateTime.of(2026, 11, 17, 11, 30, 0));
        return comment;
    }

    /**
     * 构造动态实体。
     *
     * @param postId 动态ID
     * @return 动态实体
     */
    private Post buildPost(Long postId) {
        Post post = new Post();
        post.setId(postId);
        post.setUserId(AUTHOR_ID);
        post.setContent("动态正文");
        post.setSource("web");
        post.setIsPinned(0);
        return post;
    }

    /**
     * 构造用户实体。
     *
     * @param userId 用户ID
     * @return 用户实体
     */
    private User buildUser(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setUsername("user" + userId);
        user.setNickname("昵称" + userId);
        user.setAvatarUrl("");
        return user;
    }
}
