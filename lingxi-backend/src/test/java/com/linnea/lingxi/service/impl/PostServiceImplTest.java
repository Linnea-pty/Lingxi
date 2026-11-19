package com.linnea.lingxi.service.impl;

import com.linnea.lingxi.common.exception.BusinessException;
import com.linnea.lingxi.common.exception.ErrorCode;
import com.linnea.lingxi.common.exception.SystemException;
import com.linnea.lingxi.common.idempotent.IdempotentGuard;
import com.linnea.lingxi.dto.CreatePostDTO;
import com.linnea.lingxi.dto.FeedQueryDTO;
import com.linnea.lingxi.entity.Post;
import com.linnea.lingxi.entity.User;
import com.linnea.lingxi.mapper.PostMapper;
import com.linnea.lingxi.mapper.UserMapper;
import com.linnea.lingxi.vo.FeedPageVO;
import com.linnea.lingxi.vo.PostInteractionCountVO;
import com.linnea.lingxi.vo.PostVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PostServiceImpl} 单元测试：覆盖发布入参校验、幂等三条路径与信息流分页/计数拼装。
 *
 * <p>数据访问与幂等栅栏全部用 Mockito 替身，验证的是业务分支而非 SQL 行为；
 * 真实 SQL 与数据库约束由集成测试覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
class PostServiceImplTest {

    /** 测试用发布者ID。 */
    private static final Long PUBLISHER_ID = 1001L;

    /** 测试用动态ID。 */
    private static final Long POST_ID = 9527L;

    /** 测试用幂等键。 */
    private static final String REQUEST_ID = "req-post-0001";

    /** 发布幂等场景标识，与实现保持一致。 */
    private static final String SCENE_PUBLISH = "post:publish";

    /** 动态数据访问替身。 */
    @Mock
    private PostMapper postMapper;

    /** 用户数据访问替身。 */
    @Mock
    private UserMapper userMapper;

    /** 幂等栅栏替身。 */
    @Mock
    private IdempotentGuard idempotentGuard;

    /** 被测对象。 */
    @InjectMocks
    private PostServiceImpl postService;

    @Test
    @DisplayName("缺少发布者身份：直接拒绝，避免鉴权链路被绕过时写出匿名动态")
    void createPost_missingPublisher_throwsUnauthorized() {
        // 前置
        CreatePostDTO createPostInfo = new CreatePostDTO();
        createPostInfo.setContent("正常内容");

        // 执行 + 断言
        assertThatThrownBy(() -> postService.createPost(createPostInfo, null, false))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.COMMON_UNAUTHORIZED.getCode());
        verify(postMapper, never()).insert(any(Post.class));
    }

    @Test
    @DisplayName("正文为纯空白：转成参数错误，@NotBlank 拦不住的空格串由此兜底")
    void createPost_blankContent_throwsParamError() {
        // 前置
        CreatePostDTO createPostInfo = new CreatePostDTO();
        createPostInfo.setContent("   ");

        // 执行 + 断言
        assertThatThrownBy(() -> postService.createPost(createPostInfo, PUBLISHER_ID, false))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.COMMON_PARAM_ERROR.getCode());
        verify(postMapper, never()).insert(any(Post.class));
    }

    @Test
    @DisplayName("幂等命中：返回首次结果且不再落库")
    void createPost_idempotentHit_returnsFirstResult() {
        // 前置：抢占失败（键已存在），且键值是首次落库的 postId
        CreatePostDTO createPostInfo = new CreatePostDTO();
        createPostInfo.setContent("重复提交");
        createPostInfo.setRequestId(REQUEST_ID);
        when(idempotentGuard.tryAcquire(SCENE_PUBLISH, REQUEST_ID, PUBLISHER_ID)).thenReturn(false);
        when(idempotentGuard.queryResult(SCENE_PUBLISH, REQUEST_ID, PUBLISHER_ID)).thenReturn(String.valueOf(POST_ID));
        when(idempotentGuard.isProcessing(anyString())).thenReturn(false);
        when(postMapper.selectById(POST_ID)).thenReturn(buildPost(POST_ID, LocalDateTime.now()));
        when(userMapper.selectById(PUBLISHER_ID)).thenReturn(buildUser(PUBLISHER_ID));

        // 执行
        PostVO createdPostInfo = postService.createPost(createPostInfo, PUBLISHER_ID, false);

        // 断言：返回首次动态，绝不重复插入
        assertThat(createdPostInfo.getPostId()).isEqualTo(POST_ID);
        assertThat(createdPostInfo.getLikeCount()).isZero();
        verify(postMapper, never()).insert(any(Post.class));
    }

    @Test
    @DisplayName("发布成功：落库后回填幂等结果，重复请求才能拿到同一个 postId")
    void createPost_success_bindsResult() {
        // 前置：抢占成功，插入后主键回填
        CreatePostDTO createPostInfo = new CreatePostDTO();
        createPostInfo.setContent("  正常内容  ");
        createPostInfo.setRequestId(REQUEST_ID);
        when(idempotentGuard.tryAcquire(SCENE_PUBLISH, REQUEST_ID, PUBLISHER_ID)).thenReturn(true);
        when(postMapper.insert(any(Post.class))).thenAnswer(invocation -> {
            Post newPost = invocation.getArgument(0);
            newPost.setId(POST_ID);
            return 1;
        });
        when(postMapper.selectById(POST_ID)).thenReturn(buildPost(POST_ID, LocalDateTime.now()));
        when(userMapper.selectById(PUBLISHER_ID)).thenReturn(buildUser(PUBLISHER_ID));

        // 执行
        PostVO createdPostInfo = postService.createPost(createPostInfo, PUBLISHER_ID, false);

        // 断言：正文已 trim，幂等键以 postId 作为结果回填
        assertThat(createdPostInfo.getPostId()).isEqualTo(POST_ID);
        assertThat(createdPostInfo.getContent()).isEqualTo("正常内容");
        assertThat(createdPostInfo.getSource()).isEqualTo("web");
        verify(idempotentGuard).bindResult(SCENE_PUBLISH, REQUEST_ID, PUBLISHER_ID, String.valueOf(POST_ID));
    }

    @Test
    @DisplayName("落库后回读为空：抛系统异常并释放幂等键，让客户端能立即重试")
    void createPost_readBackMissing_releasesKey() {
        // 前置：插入成功但回读为空
        CreatePostDTO createPostInfo = new CreatePostDTO();
        createPostInfo.setContent("正常内容");
        createPostInfo.setRequestId(REQUEST_ID);
        when(idempotentGuard.tryAcquire(SCENE_PUBLISH, REQUEST_ID, PUBLISHER_ID)).thenReturn(true);
        when(postMapper.insert(any(Post.class))).thenAnswer(invocation -> {
            Post newPost = invocation.getArgument(0);
            newPost.setId(POST_ID);
            return 1;
        });
        when(postMapper.selectById(POST_ID)).thenReturn(null);

        // 执行 + 断言：异常上抛且释放幂等键
        assertThatThrownBy(() -> postService.createPost(createPostInfo, PUBLISHER_ID, false))
                .isInstanceOf(SystemException.class);
        verify(idempotentGuard).release(SCENE_PUBLISH, REQUEST_ID, PUBLISHER_ID);
    }

    @Test
    @DisplayName("信息流无数据：空页不触发计数查询，避免 IN () 语法错误")
    void queryFeed_empty_skipsCountQuery() {
        // 前置
        FeedQueryDTO feedQueryInfo = new FeedQueryDTO();
        feedQueryInfo.setSize(10);
        when(postMapper.selectFeedList(any(), any(), any(), anyInt())).thenReturn(new ArrayList<>());

        // 执行
        FeedPageVO feedPage = postService.queryFeed(feedQueryInfo);

        // 断言
        assertThat(feedPage.getPostList()).isEmpty();
        assertThat(feedPage.getHasMore()).isFalse();
        assertThat(feedPage.getNextCursor()).isNull();
        verify(postMapper, never()).selectPostCountList(anyList());
    }

    @Test
    @DisplayName("信息流多取一条探测：裁剪探测行并生成下一页游标")
    void queryFeed_hasMore_trimsProbeRowAndBuildsCursor() {
        // 前置：请求 2 条，仓储返回 3 条表示还有下一页
        FeedQueryDTO feedQueryInfo = new FeedQueryDTO();
        feedQueryInfo.setSize(2);
        LocalDateTime baseTime = LocalDateTime.of(2026, 11, 17, 10, 0, 0);
        List<PostVO> feedRows = new ArrayList<>(List.of(
                buildFeedRow(3L, baseTime),
                buildFeedRow(2L, baseTime.minusMinutes(1)),
                buildFeedRow(1L, baseTime.minusMinutes(2))));
        when(postMapper.selectFeedList(any(), any(), any(), eq(3))).thenReturn(feedRows);
        when(postMapper.selectPostCountList(anyList())).thenReturn(Collections.emptyList());

        // 执行
        FeedPageVO feedPage = postService.queryFeed(feedQueryInfo);

        // 断言：只返回 2 条，游标基于第 2 条（本页最后一条）生成
        assertThat(feedPage.getPostList()).hasSize(2);
        assertThat(feedPage.getHasMore()).isTrue();
        assertThat(feedPage.getNextCursor()).isNotBlank();
    }

    @Test
    @DisplayName("互动计数回填：按 postId 匹配，缺失时兜底 0 而不是 null")
    void queryFeed_fillsInteractionCount() {
        // 前置：两个动态，只有第一个有计数数据
        FeedQueryDTO feedQueryInfo = new FeedQueryDTO();
        feedQueryInfo.setSize(10);
        LocalDateTime baseTime = LocalDateTime.of(2026, 11, 17, 10, 0, 0);
        when(postMapper.selectFeedList(any(), any(), any(), anyInt())).thenReturn(new ArrayList<>(List.of(
                buildFeedRow(3L, baseTime),
                buildFeedRow(2L, baseTime.minusMinutes(1)))));
        PostInteractionCountVO countItem = new PostInteractionCountVO();
        countItem.setPostId(3L);
        countItem.setLikeCount(8L);
        countItem.setCommentCount(3L);
        when(postMapper.selectPostCountList(anyList())).thenReturn(List.of(countItem));

        // 执行
        FeedPageVO feedPage = postService.queryFeed(feedQueryInfo);

        // 断言：命中的动态取真实计数，未命中的兜底 0
        assertThat(feedPage.getPostList().get(0).getLikeCount()).isEqualTo(8L);
        assertThat(feedPage.getPostList().get(0).getCommentCount()).isEqualTo(3L);
        assertThat(feedPage.getPostList().get(1).getLikeCount()).isZero();
        assertThat(feedPage.getPostList().get(1).getCommentCount()).isZero();
    }

    /**
     * 构造持久化层返回的动态实体。
     *
     * @param postId     动态ID
     * @param createTime 创建时间
     * @return 动态实体
     */
    private Post buildPost(Long postId, LocalDateTime createTime) {
        Post post = new Post();
        post.setId(postId);
        post.setUserId(PUBLISHER_ID);
        post.setContent("正常内容");
        post.setSource("web");
        post.setIsPinned(0);
        post.setCreateTime(createTime);
        return post;
    }

    /**
     * 构造信息流查询返回的行对象。
     *
     * @param postId     动态ID
     * @param createTime 创建时间
     * @return 信息流行
     */
    private PostVO buildFeedRow(Long postId, LocalDateTime createTime) {
        PostVO feedRow = new PostVO();
        feedRow.setPostId(postId);
        feedRow.setContent("feed content");
        feedRow.setSource("web");
        feedRow.setIsPinned(Boolean.FALSE);
        feedRow.setCreateTime(createTime);
        feedRow.setAuthorId(PUBLISHER_ID);
        feedRow.setAuthorNickname("author");
        feedRow.setAuthorAvatarUrl("");
        return feedRow;
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
        user.setUsername("author");
        user.setNickname("author");
        user.setAvatarUrl("");
        return user;
    }
}
