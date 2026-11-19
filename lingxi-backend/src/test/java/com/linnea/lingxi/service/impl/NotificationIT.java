package com.linnea.lingxi.service.impl;

import com.linnea.lingxi.common.enums.NotificationType;
import com.linnea.lingxi.dto.CreateCommentDTO;
import com.linnea.lingxi.dto.CreatePostDTO;
import com.linnea.lingxi.dto.NotificationQueryDTO;
import com.linnea.lingxi.dto.RegisterDTO;
import com.linnea.lingxi.service.AuthService;
import com.linnea.lingxi.service.CommentService;
import com.linnea.lingxi.service.NotificationService;
import com.linnea.lingxi.service.PostLikeService;
import com.linnea.lingxi.service.PostService;
import com.linnea.lingxi.service.UserService;
import com.linnea.lingxi.support.AbstractIntegrationTest;
import com.linnea.lingxi.vo.CommentVO;
import com.linnea.lingxi.vo.LoginVO;
import com.linnea.lingxi.vo.NotificationPageVO;
import com.linnea.lingxi.vo.NotificationVO;
import com.linnea.lingxi.vo.PostVO;
import com.linnea.lingxi.vo.UnreadCountVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知模块集成测试，跑在真实 MySQL 上。
 *
 * <p>WHY 必须真实数据库：三个关键行为都依赖 SQL 语义——① 点赞/关注靠唯一键与 upsert 分支决定「是否首次」，
 * 从而决定要不要通知；② 通知列表的键集游标是「时间 + ID」复合比较，同一秒内多条通知时最容易出现重复或漏项；
 * ③ 标记已读的越权拦截写在 {@code WHERE user_id = ?} 里，用替身验证不到这一句。</p>
 */
@Transactional
class NotificationIT extends AbstractIntegrationTest {

    /** 测试账号密码，满足 8~64 位要求。 */
    private static final String PASSWORD = "lingxi@2026";

    /** 用户名序号：同一测试内多次注册需要不同用户名。 */
    private static final AtomicInteger USERNAME_SEQ = new AtomicInteger();

    /** 一级评论的 parentId 约定值。 */
    private static final long ROOT_PARENT_ID = 0L;

    /** 认证业务服务：注册即登录，用于拿用户ID。 */
    @Autowired
    private AuthService authService;

    /** 动态业务服务：造「被点赞/被评论」的数据。 */
    @Autowired
    private PostService postService;

    /** 点赞业务服务：验证点赞通知。 */
    @Autowired
    private PostLikeService postLikeService;

    /** 评论业务服务：验证评论与回复通知。 */
    @Autowired
    private CommentService commentService;

    /** 用户业务服务：验证关注通知。 */
    @Autowired
    private UserService userService;

    /** 通知业务服务：被测对象。 */
    @Autowired
    private NotificationService notificationService;

    @Test
    @DisplayName("点赞：他人点赞产生一条未读通知，类型描述可直接展示")
    void likePost_generatesUnreadNotification() {
        // 前置：作者发动态，路人点赞
        Long authorId = registerUser();
        Long likerId = registerUser();
        PostVO publishedPost = publishPost(authorId, "被点赞的动态");
        postLikeService.likePost(publishedPost.getPostId(), likerId);

        // 步骤1：作者收到一条通知
        NotificationPageVO notificationPage = notificationService.queryNotificationPage(authorId, buildQuery(false, 10));
        assertThat(notificationPage.getNotificationList()).hasSize(1);

        // 步骤2：类型、触发者、未读状态、关联动态都正确
        NotificationVO notificationInfo = notificationPage.getNotificationList().get(0);
        assertThat(notificationInfo.getType()).isEqualTo(NotificationType.LIKE_POST.getCode());
        assertThat(notificationInfo.getTypeDescription()).isEqualTo(NotificationType.LIKE_POST.getDescription());
        assertThat(notificationInfo.getActorId()).isEqualTo(likerId);
        assertThat(notificationInfo.getIsRead()).isFalse();
        assertThat(notificationInfo.getPostId()).isEqualTo(publishedPost.getPostId());
        assertThat(notificationPage.getUnreadCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("点赞：赞自己的动态不产生通知")
    void likePost_whenSelfLike_generatesNoNotification() {
        // 前置：作者点赞自己的动态
        Long authorId = registerUser();
        PostVO publishedPost = publishPost(authorId, "自己赞自己");
        postLikeService.likePost(publishedPost.getPostId(), authorId);

        // 步骤1：通知列表为空
        assertThat(notificationService.queryNotificationPage(authorId, buildQuery(false, 10)).getNotificationList())
                .isEmpty();
        assertThat(notificationService.queryUnreadCount(authorId).getUnreadCount()).isZero();
    }

    @Test
    @DisplayName("点赞：重复点赞只通知一次")
    void likePost_whenDuplicate_likesNotifyOnce() {
        // 前置：同一人连续点赞两次
        Long authorId = registerUser();
        Long likerId = registerUser();
        PostVO publishedPost = publishPost(authorId, "被点了两次赞");
        postLikeService.likePost(publishedPost.getPostId(), likerId);
        postLikeService.likePost(publishedPost.getPostId(), likerId);

        // 步骤1：仍然只有一条通知（第二次走唯一键冲突分支，不投递）
        assertThat(notificationService.queryNotificationPage(authorId, buildQuery(false, 10)).getNotificationList())
                .hasSize(1);
    }

    @Test
    @DisplayName("评论：一级评论通知动态作者，目标ID是评论ID")
    void createComment_notifiesPostAuthor() {
        // 前置：作者发动态，另一个人评论
        Long authorId = registerUser();
        Long commenterId = registerUser();
        PostVO publishedPost = publishPost(authorId, "被评论的动态");
        CommentVO createdComment = commentService.createComment(buildCommentInfo("一条评论", ROOT_PARENT_ID),
                publishedPost.getPostId(), commenterId);

        // 步骤1：作者收到 COMMENT_POST 通知，targetId 指向评论
        List<NotificationVO> notificationList = notificationService
                .queryNotificationPage(authorId, buildQuery(false, 10)).getNotificationList();
        assertThat(notificationList).hasSize(1);
        assertThat(notificationList.get(0).getType()).isEqualTo(NotificationType.COMMENT_POST.getCode());
        assertThat(notificationList.get(0).getTargetId()).isEqualTo(createdComment.getCommentId());
        assertThat(notificationList.get(0).getPostId()).isEqualTo(publishedPost.getPostId());
    }

    @Test
    @DisplayName("评论：回复只通知被回复人，动态作者不重复收第二条")
    void createReply_notifiesOnlyRepliedUser() {
        // 前置：A 发动态，B 评论，C 回复 B
        Long authorId = registerUser();
        Long firstCommenterId = registerUser();
        Long replierId = registerUser();
        PostVO publishedPost = publishPost(authorId, "有讨论串的动态");
        CommentVO rootComment = commentService.createComment(buildCommentInfo("一级评论", ROOT_PARENT_ID),
                publishedPost.getPostId(), firstCommenterId);
        CommentVO replyComment = commentService.createComment(buildCommentInfo("回复一级评论", rootComment.getCommentId()),
                publishedPost.getPostId(), replierId);

        // 步骤1：被回复人收到 REPLY_COMMENT
        List<NotificationVO> repliedUserNotifications = notificationService
                .queryNotificationPage(firstCommenterId, buildQuery(false, 10)).getNotificationList();
        assertThat(repliedUserNotifications).hasSize(1);
        assertThat(repliedUserNotifications.get(0).getType()).isEqualTo(NotificationType.REPLY_COMMENT.getCode());
        assertThat(repliedUserNotifications.get(0).getTargetId()).isEqualTo(replyComment.getCommentId());

        // 步骤2：动态作者只收到 B 的那条评论通知，没有因为「回复」再收一条
        List<NotificationVO> authorNotifications = notificationService
                .queryNotificationPage(authorId, buildQuery(false, 10)).getNotificationList();
        assertThat(authorNotifications).hasSize(1);
        assertThat(authorNotifications.get(0).getType()).isEqualTo(NotificationType.COMMENT_POST.getCode());
    }

    @Test
    @DisplayName("关注：首次关注通知一次，重复关注不重复通知")
    void followUser_notifiesOnlyOnFirstFollow() {
        // 前置：被关注的人与关注者
        Long followedUserId = registerUser();
        Long followerUserId = registerUser();

        // 步骤1：首次关注产生 FOLLOW 通知，目标ID 是关注发起方（便于点击跳主页）
        userService.followUser(followedUserId, followerUserId);
        List<NotificationVO> notificationList = notificationService
                .queryNotificationPage(followedUserId, buildQuery(false, 10)).getNotificationList();
        assertThat(notificationList).hasSize(1);
        assertThat(notificationList.get(0).getType()).isEqualTo(NotificationType.FOLLOW.getCode());
        assertThat(notificationList.get(0).getTargetId()).isEqualTo(followerUserId);
        assertThat(notificationList.get(0).getPostId()).isZero();

        // 步骤2：重复关注只刷新关系，不再打扰对方
        userService.followUser(followedUserId, followerUserId);
        assertThat(notificationService.queryNotificationPage(followedUserId, buildQuery(false, 10))
                .getNotificationList()).hasSize(1);
    }

    @Test
    @DisplayName("列表：size=2 靠游标翻完三页不重不漏，unreadOnly 只返回未读")
    void queryNotificationPage_paginatesAndFiltersUnread() {
        // 前置：作者的一条动态被三个人点赞 → 三条通知
        Long authorId = registerUser();
        PostVO publishedPost = publishPost(authorId, "被三个人点赞");
        postLikeService.likePost(publishedPost.getPostId(), registerUser());
        postLikeService.likePost(publishedPost.getPostId(), registerUser());
        postLikeService.likePost(publishedPost.getPostId(), registerUser());

        // 步骤1：第一页 2 条且还有下一页
        NotificationPageVO firstPage = notificationService.queryNotificationPage(authorId, buildQuery(false, 2));
        assertThat(firstPage.getNotificationList()).hasSize(2);
        assertThat(firstPage.getHasMore()).isTrue();
        assertThat(firstPage.getNextCursor()).isNotBlank();

        // 步骤2：第二页 1 条，没有更多
        NotificationPageVO secondPage = notificationService
                .queryNotificationPage(authorId, buildQueryCursor(firstPage.getNextCursor(), false, 2));
        assertThat(secondPage.getNotificationList()).hasSize(1);
        assertThat(secondPage.getHasMore()).isFalse();
        assertThat(secondPage.getNextCursor()).isNull();

        // 步骤3：两页合起来 3 条互不重复
        List<Long> pageNotificationIds = new java.util.ArrayList<>();
        firstPage.getNotificationList().forEach(notificationInfo ->
                pageNotificationIds.add(notificationInfo.getNotificationId()));
        secondPage.getNotificationList().forEach(notificationInfo ->
                pageNotificationIds.add(notificationInfo.getNotificationId()));
        assertThat(pageNotificationIds).hasSize(3).doesNotHaveDuplicates();

        // 步骤4：全部标记已读后，unreadOnly 的列表为空、未读数为 0，而全量列表依然是 3 条
        notificationService.markAllRead(authorId);
        assertThat(notificationService.queryNotificationPage(authorId, buildQueryUnreadOnly(true, 10))
                .getNotificationList()).isEmpty();
        assertThat(notificationService.queryUnreadCount(authorId).getUnreadCount()).isZero();
        assertThat(notificationService.queryNotificationPage(authorId, buildQuery(false, 10))
                .getNotificationList()).hasSize(3);
    }

    @Test
    @DisplayName("已读：单条标记后未读数减一，他人无法标记我的通知")
    void markRead_updatesUnreadCountAndRejectsOtherUser() {
        // 前置：作者被点赞一次
        Long authorId = registerUser();
        Long likerId = registerUser();
        PostVO publishedPost = publishPost(authorId, "待已读的动态");
        postLikeService.likePost(publishedPost.getPostId(), likerId);
        Long notificationId = notificationService.queryNotificationPage(authorId, buildQuery(false, 10))
                .getNotificationList().get(0).getNotificationId();

        // 步骤1：别人（likerId）拿我的通知ID去标记，接口按幂等成功返回，但我的未读数不变
        UnreadCountVO otherUserResult = notificationService.markRead(notificationId, likerId);
        assertThat(otherUserResult.getUnreadCount()).isZero();
        assertThat(notificationService.queryUnreadCount(authorId).getUnreadCount()).isEqualTo(1L);

        // 步骤2：本人标记后未读数归零
        UnreadCountVO ownerResult = notificationService.markRead(notificationId, authorId);
        assertThat(ownerResult.getUnreadCount()).isZero();
        assertThat(notificationService.queryNotificationPage(authorId, buildQuery(false, 10))
                .getNotificationList().get(0).getIsRead()).isTrue();

        // 步骤3：重复标记同一通知仍然成功（幂等），不会报错
        assertThat(notificationService.markRead(notificationId, authorId).getUnreadCount()).isZero();
    }

    /**
     * 注册一个随机用户名的新用户。
     *
     * @return 新用户ID
     */
    private Long registerUser() {
        // 步骤1：用户名加自增序号，保证同一测试内多次注册不撞唯一键
        String username = "notify_" + USERNAME_SEQ.incrementAndGet() + "_" + System.nanoTime() % 100_000L;
        RegisterDTO registerInfo = new RegisterDTO();
        registerInfo.setUsername(username);
        registerInfo.setPassword(PASSWORD);
        registerInfo.setNickname(username);
        // 步骤2：注册即登录，直接取用户ID
        LoginVO loginResult = authService.register(registerInfo);
        return loginResult.getUserInfo().getUserId();
    }

    /**
     * 发布一条动态。
     *
     * @param authorId 作者用户ID
     * @param content  正文
     * @return 新建动态
     */
    private PostVO publishPost(Long authorId, String content) {
        // 步骤1：组装并由「普通用户」身份发布（agentRequest=false，不代表 Agent 发帖）
        CreatePostDTO createPostInfo = new CreatePostDTO();
        createPostInfo.setContent(content);
        return postService.createPost(createPostInfo, authorId, false);
    }

    /**
     * 构造评论请求参数。
     *
     * @param content  正文
     * @param parentId 父评论ID，一级评论传 0
     * @return 评论请求参数
     */
    private CreateCommentDTO buildCommentInfo(String content, Long parentId) {
        // 步骤1：写入正文与父评论
        CreateCommentDTO createCommentInfo = new CreateCommentDTO();
        createCommentInfo.setContent(content);
        createCommentInfo.setParentId(parentId);
        return createCommentInfo;
    }

    /**
     * 构造通知查询参数（不带游标）。
     *
     * @param unreadOnly 是否只看未读
     * @param size       每页条数
     * @return 查询参数
     */
    private NotificationQueryDTO buildQuery(boolean unreadOnly, int size) {
        // 步骤1：写入开关与条数
        return buildQueryCursor(null, unreadOnly, size);
    }

    /**
     * 构造「只看未读」的通知查询参数。
     *
     * @param unreadOnly 是否只看未读
     * @param size       每页条数
     * @return 查询参数
     */
    private NotificationQueryDTO buildQueryUnreadOnly(boolean unreadOnly, int size) {
        // 步骤1：同 buildQuery，单独命名让调用处意图更清楚
        return buildQueryCursor(null, unreadOnly, size);
    }

    /**
     * 构造带游标的通知查询参数。
     *
     * @param cursor     游标，首页传 null
     * @param unreadOnly 是否只看未读
     * @param size       每页条数
     * @return 查询参数
     */
    private NotificationQueryDTO buildQueryCursor(String cursor, boolean unreadOnly, int size) {
        // 步骤1：写入游标、开关与条数
        NotificationQueryDTO queryInfo = new NotificationQueryDTO();
        queryInfo.setCursor(cursor);
        queryInfo.setUnreadOnly(unreadOnly);
        queryInfo.setSize(size);
        return queryInfo;
    }
}
