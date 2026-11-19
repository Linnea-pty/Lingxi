package com.linnea.lingxi.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.linnea.lingxi.common.enums.UserRole;
import com.linnea.lingxi.common.enums.UserStatus;
import com.linnea.lingxi.common.exception.BusinessException;
import com.linnea.lingxi.dto.AdminUserQueryDTO;
import com.linnea.lingxi.dto.BanUserDTO;
import com.linnea.lingxi.dto.CreateCommentDTO;
import com.linnea.lingxi.dto.CreatePostDTO;
import com.linnea.lingxi.dto.LoginDTO;
import com.linnea.lingxi.dto.RegisterDTO;
import com.linnea.lingxi.entity.User;
import com.linnea.lingxi.mapper.CommentMapper;
import com.linnea.lingxi.mapper.PostMapper;
import com.linnea.lingxi.mapper.UserMapper;
import com.linnea.lingxi.security.ApiKeyCodec;
import com.linnea.lingxi.service.AdminService;
import com.linnea.lingxi.service.AuthService;
import com.linnea.lingxi.service.CommentService;
import com.linnea.lingxi.service.PostService;
import com.linnea.lingxi.support.AbstractIntegrationTest;
import com.linnea.lingxi.vo.AdminUserPageVO;
import com.linnea.lingxi.vo.AdminUserVO;
import com.linnea.lingxi.vo.CommentVO;
import com.linnea.lingxi.vo.LoginVO;
import com.linnea.lingxi.vo.PostVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理员后台集成测试。
 *
 * <p>WHY 用 MockMvc 而不是直接调 Service：本批有三件核心行为只发生在 HTTP 层——
 * ① {@code @PreAuthorize("hasRole('ADMIN')")} 是否真的拦住普通用户与 Agent 密钥（403 + 统一返回体）；
 * ② 封禁账号的**旧访问令牌**是否在下一次请求立即被拒（靠过滤器回查账号，不是 Service 内判断）；
 * ③ 封禁后 Agent 的 X-Api-Key 是否同样失效。用 Service 调用只能验证业务分支，验证不到安全链。</p>
 *
 * <p>管理员账号通过「直接改库 role='ADMIN'」构造，与生产上运维 SQL 授权的路径一致：
 * 项目不提供「自己把自己变成管理员」的接口。</p>
 */
@Transactional
@AutoConfigureMockMvc
class AdminConsoleIT extends AbstractIntegrationTest {

    /** 测试账号密码，满足 8~64 位要求。 */
    private static final String PASSWORD = "lingxi@2026";

    /** 用户名序号：同一测试内多次注册需要不同用户名。 */
    private static final AtomicInteger USERNAME_SEQ = new AtomicInteger();

    /** 一级评论的 parentId 约定值。 */
    private static final long ROOT_PARENT_ID = 0L;

    /** 后台接口根路径。 */
    private static final String ADMIN_API = "/api/v1/admin";

    /** 认证业务服务：注册、登录。 */
    @Autowired
    private AuthService authService;

    /** 动态业务服务：造被治理的内容。 */
    @Autowired
    private PostService postService;

    /** 评论业务服务：造被治理的评论。 */
    @Autowired
    private CommentService commentService;

    /** 后台业务服务：被测对象。 */
    @Autowired
    private AdminService adminService;

    /** 用户数据访问：构造管理员、回填 API Key、按ID取用户名。 */
    @Autowired
    private UserMapper userMapper;

    /** 动态数据访问：核对级联删除结果。 */
    @Autowired
    private PostMapper postMapper;

    /** 评论数据访问：核对级联删除结果。 */
    @Autowired
    private CommentMapper commentMapper;

    /** HTTP 层测试入口：经过完整 Spring Security 过滤器链。 */
    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("权限：普通用户访问后台接口 403，管理员 200")
    void adminApi_rejectsNormalUserAndAllowsAdmin() throws Exception {
        // 前置：一个普通用户与一个管理员
        Long normalUserId = registerUser();
        Long adminUserId = registerUser();
        promoteToAdmin(adminUserId);
        String normalToken = loginToken(normalUserId);
        String adminToken = loginToken(adminUserId);

        // 步骤1：普通用户被 @PreAuthorize 拦下，返回 403 与统一返回体
        mockMvc.perform(get(ADMIN_API + "/users").header("Authorization", "Bearer " + normalToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));

        // 步骤2：管理员正常拿到列表
        mockMvc.perform(get(ADMIN_API + "/users").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.userList").isArray())
                .andExpect(jsonPath("$.data.hasMore").exists());
    }

    @Test
    @DisplayName("权限：Agent 密钥不具备后台权限")
    void adminApi_rejectsAgentApiKey() throws Exception {
        // 前置：给普通用户回填一把 Agent 密钥
        Long userId = registerUser();
        String rawApiKey = "lingxi_admin_it_key_" + System.nanoTime();
        bindApiKey(userId, rawApiKey);

        // 步骤1：带 X-Api-Key 调后台，只拿到 ROLE_AGENT，被 hasRole('ADMIN') 拦下
        mockMvc.perform(get(ADMIN_API + "/users").header("X-Api-Key", rawApiKey))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
    }

    @Test
    @DisplayName("用户列表：关键字前缀筛选 + 游标翻页不重不漏，状态筛选生效")
    void queryUserPage_filtersByPrefixAndPaginates() {
        // 前置：三个同前缀用户 + 一个不同前缀用户，避免被其它数据干扰
        String prefix = "adm_page_" + USERNAME_SEQ.incrementAndGet() + "_";
        Long firstUserId = registerUser(prefix + "a");
        Long secondUserId = registerUser(prefix + "b");
        Long thirdUserId = registerUser(prefix + "c");
        registerUser("other_" + System.nanoTime() % 100_000L);
        promoteToAdmin(firstUserId);

        // 步骤1：第一页 2 条，还有下一页
        AdminUserPageVO firstPage = adminService.queryUserPage(buildUserQuery(prefix, null, 2));
        assertThat(firstPage.getUserList()).hasSize(2);
        assertThat(firstPage.getHasMore()).isTrue();
        assertThat(firstPage.getNextCursor()).isNotBlank();

        // 步骤2：第二页 1 条，没有更多
        AdminUserQueryDTO secondQuery = buildUserQuery(prefix, null, 2);
        secondQuery.setCursor(firstPage.getNextCursor());
        AdminUserPageVO secondPage = adminService.queryUserPage(secondQuery);
        assertThat(secondPage.getUserList()).hasSize(1);
        assertThat(secondPage.getHasMore()).isFalse();
        assertThat(secondPage.getNextCursor()).isNull();

        // 步骤3：两页合起来正好是这三个用户，互不重复
        List<Long> pagedUserIdList = new ArrayList<>();
        firstPage.getUserList().forEach(userInfo -> pagedUserIdList.add(userInfo.getUserId()));
        secondPage.getUserList().forEach(userInfo -> pagedUserIdList.add(userInfo.getUserId()));
        assertThat(pagedUserIdList)
                .containsExactlyInAnyOrder(firstUserId, secondUserId, thirdUserId)
                .doesNotHaveDuplicates();

        // 步骤4：封禁其中一个后，status=0 只筛出被封禁的那个
        adminService.banUser(secondUserId, buildBanInfo("列表筛选验证"), firstUserId);
        AdminUserPageVO bannedPage = adminService.queryUserPage(
                buildUserQuery(prefix, UserStatus.BANNED.getCode(), 10));
        assertThat(bannedPage.getUserList()).hasSize(1);
        AdminUserVO bannedUserInfo = bannedPage.getUserList().get(0);
        assertThat(bannedUserInfo.getUserId()).isEqualTo(secondUserId);
        assertThat(bannedUserInfo.getStatus()).isEqualTo(UserStatus.BANNED.getCode());
        assertThat(bannedUserInfo.getStatusDescription()).isEqualTo(UserStatus.BANNED.getDescription());
        assertThat(bannedUserInfo.getRoleDescription()).isEqualTo(UserRole.USER.getDescription());
    }

    @Test
    @DisplayName("封禁：旧访问令牌立即失效，登录被拒，解封后可重新登录")
    void banUser_invalidatesExistingTokenAndBlocksLogin() throws Exception {
        // 前置：普通用户已登录，管理员准备封禁
        Long operatorUserId = registerUser();
        promoteToAdmin(operatorUserId);
        Long targetUserId = registerUser();
        String targetToken = loginToken(targetUserId);

        // 步骤0：封禁前目标用户的令牌可用
        mockMvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        // 步骤1：管理员封禁，原因与状态落库
        AdminUserVO bannedUserInfo = adminService.banUser(targetUserId, buildBanInfo("违规内容"), operatorUserId);
        assertThat(bannedUserInfo.getStatus()).isEqualTo(UserStatus.BANNED.getCode());
        assertThat(bannedUserInfo.getBanReason()).isEqualTo("违规内容");

        // 步骤2：旧令牌在下一次请求就被拒（401），不必等它自然过期
        mockMvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));

        // 步骤3：重新登录也被拒，且错误码是 USER_BANNED（不是「用户名或密码错误」）
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildLoginJson(targetUserId)))
                .andExpect(jsonPath("$.code").value("USER_BANNED"));

        // 步骤4：解封后可以重新登录，封禁原因清空
        AdminUserVO unbannedUserInfo = adminService.unbanUser(targetUserId, operatorUserId);
        assertThat(unbannedUserInfo.getStatus()).isEqualTo(UserStatus.NORMAL.getCode());
        assertThat(unbannedUserInfo.getBanReason()).isEmpty();
        assertThat(loginToken(targetUserId)).isNotBlank();
    }

    @Test
    @DisplayName("封禁：不能封自己、不能封管理员、目标不存在报 USER_NOT_FOUND")
    void banUser_rejectsSelfAdminAndMissingUser() {
        // 前置：两个管理员
        Long operatorUserId = registerUser();
        promoteToAdmin(operatorUserId);
        Long anotherAdminId = registerUser();
        promoteToAdmin(anotherAdminId);

        // 步骤1：封自己被拒绝，避免自锁后台
        assertThat(catchBusinessCode(() -> adminService.banUser(operatorUserId, buildBanInfo("自封"), operatorUserId)))
                .isEqualTo("COMMON_FORBIDDEN");

        // 步骤2：封另一个管理员被拒绝，避免后台互相锁死
        assertThat(catchBusinessCode(() -> adminService.banUser(anotherAdminId, buildBanInfo("互封"), operatorUserId)))
                .isEqualTo("COMMON_FORBIDDEN");

        // 步骤3：目标不存在
        assertThat(catchBusinessCode(() -> adminService.banUser(999_999L, buildBanInfo("幽灵"), operatorUserId)))
                .isEqualTo("USER_NOT_FOUND");
    }

    @Test
    @DisplayName("封禁：Agent 密钥随账号一起失效")
    void banUser_invalidatesAgentApiKey() throws Exception {
        // 前置：普通用户持有 API Key，封禁前可正常调用
        Long operatorUserId = registerUser();
        promoteToAdmin(operatorUserId);
        Long agentUserId = registerUser();
        String rawApiKey = "lingxi_agent_it_key_" + System.nanoTime();
        bindApiKey(agentUserId, rawApiKey);

        mockMvc.perform(get("/api/v1/notifications/unread-count").header("X-Api-Key", rawApiKey))
                .andExpect(jsonPath("$.code").value("0"));

        // 步骤1：封禁账号
        adminService.banUser(agentUserId, buildBanInfo("机器端滥用"), operatorUserId);

        // 步骤2：同一把密钥再次调用被 401 拒绝
        mockMvc.perform(get("/api/v1/notifications/unread-count").header("X-Api-Key", rawApiKey))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("内容治理：管理员删除他人动态，级联删除其下评论")
    void removePost_deletesOthersContentWithComments() {
        // 前置：普通用户发动态，另一个人评论
        Long operatorUserId = registerUser();
        promoteToAdmin(operatorUserId);
        Long authorUserId = registerUser();
        Long commenterUserId = registerUser();
        PostVO publishedPost = publishPost(authorUserId, "违规动态");
        CommentVO createdComment = commentService.createComment(buildCommentInfo("违规评论", ROOT_PARENT_ID),
                publishedPost.getPostId(), commenterUserId);

        // 步骤1：管理员删除，不因「不是作者」报错
        adminService.removePost(publishedPost.getPostId(), operatorUserId);

        // 步骤2：动态与评论都被逻辑删除（MyBatis-Plus 自动过滤已删行）
        assertThat(postMapper.selectById(publishedPost.getPostId())).isNull();
        assertThat(commentMapper.selectById(createdComment.getCommentId())).isNull();
    }

    @Test
    @DisplayName("内容治理：管理员删除他人评论，级联删除其下回复")
    void removeComment_deletesOthersCommentWithReplies() {
        // 前置：A 发动态，B 评论，C 回复 B
        Long operatorUserId = registerUser();
        promoteToAdmin(operatorUserId);
        Long authorUserId = registerUser();
        Long rootCommenterId = registerUser();
        Long replierId = registerUser();
        PostVO publishedPost = publishPost(authorUserId, "有争议的动态");
        CommentVO rootComment = commentService.createComment(buildCommentInfo("一级评论", ROOT_PARENT_ID),
                publishedPost.getPostId(), rootCommenterId);
        CommentVO replyComment = commentService.createComment(buildCommentInfo("回复一级评论", rootComment.getCommentId()),
                publishedPost.getPostId(), replierId);

        // 步骤1：管理员删除一级评论
        adminService.removeComment(rootComment.getCommentId(), operatorUserId);

        // 步骤2：一级评论与它下面的回复一起消失，不留孤儿回复
        assertThat(commentMapper.selectById(rootComment.getCommentId())).isNull();
        assertThat(commentMapper.selectById(replyComment.getCommentId())).isNull();

        // 步骤3：动态本身不受影响
        assertThat(postMapper.selectById(publishedPost.getPostId())).isNotNull();
    }

    /**
     * 注册一个随机用户名的新用户。
     *
     * @return 新用户ID
     */
    private Long registerUser() {
        // 步骤1：用户名加自增序号与纳秒后缀，保证同一测试内多次注册不撞唯一键
        return registerUser("adm_" + USERNAME_SEQ.incrementAndGet() + "_" + System.nanoTime() % 100_000L);
    }

    /**
     * 按指定用户名注册新用户。
     *
     * @param username 用户名
     * @return 新用户ID
     */
    private Long registerUser(String username) {
        // 步骤1：组装注册参数
        RegisterDTO registerInfo = new RegisterDTO();
        registerInfo.setUsername(username);
        registerInfo.setPassword(PASSWORD);
        registerInfo.setNickname(username);
        // 步骤2：注册即登录，取用户ID
        LoginVO loginResult = authService.register(registerInfo);
        return loginResult.getUserInfo().getUserId();
    }

    /**
     * 把账号提升为管理员。
     *
     * @param userId 用户ID
     */
    private void promoteToAdmin(Long userId) {
        // 步骤1：直接改库，与生产上运维授权管理员的路径一致
        User updateUser = new User();
        updateUser.setRole(UserRole.ADMIN.getCode());
        userMapper.update(updateUser, new LambdaUpdateWrapper<User>().eq(User::getId, userId));
    }

    /**
     * 给账号绑定一把 Agent API Key（库里只存摘要）。
     *
     * @param userId    用户ID
     * @param rawApiKey 明文密钥，仅测试内使用
     */
    private void bindApiKey(Long userId, String rawApiKey) {
        // 步骤1：写入摘要，与生产签发流程一致
        User updateUser = new User();
        updateUser.setApiKey(ApiKeyCodec.digest(rawApiKey));
        userMapper.update(updateUser, new LambdaUpdateWrapper<User>().eq(User::getId, userId));
    }

    /**
     * 登录并取访问令牌。
     *
     * @param userId 用户ID
     * @return 访问令牌
     */
    private String loginToken(Long userId) {
        // 步骤1：用已知密码登录，返回访问令牌
        return authService.login(buildLoginInfo(userId)).getAccessToken();
    }

    /**
     * 按用户ID构造登录请求的 JSON 文本。
     *
     * <p>WHY 手写 JSON 而不注入 Jackson：Spring Boot 4 默认使用 Jackson 3，
     * 容器里没有 {@code com.fasterxml.jackson.databind.ObjectMapper} 这个 bean；
     * 这里只有一个字段固定的登录请求体，拼字符串比引入一套序列化配置更直接。</p>
     *
     * @param userId 用户ID
     * @return 登录请求体 JSON
     */
    private String buildLoginJson(Long userId) {
        // 步骤1：用户名由测试生成（仅字母、数字、下划线），不存在转义问题
        User existedUser = userMapper.selectById(userId);
        // 步骤2：拼出登录请求体
        return "{\"username\":\"" + existedUser.getUsername() + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    /**
     * 按用户ID构造登录参数（用户名从库里回查）。
     *
     * @param userId 用户ID
     * @return 登录参数
     */
    private LoginDTO buildLoginInfo(Long userId) {
        // 步骤1：回查用户名——登录只接受用户名，对外也没有「按ID查用户名」的接口
        User existedUser = userMapper.selectById(userId);
        // 步骤2：写入用户名与固定测试密码
        LoginDTO loginInfo = new LoginDTO();
        loginInfo.setUsername(existedUser.getUsername());
        loginInfo.setPassword(PASSWORD);
        return loginInfo;
    }

    /**
     * 发布一条动态。
     *
     * @param authorId 作者用户ID
     * @param content  正文
     * @return 新建动态
     */
    private PostVO publishPost(Long authorId, String content) {
        // 步骤1：以普通用户身份发布（agent=false）
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
     * 构造封禁请求参数。
     *
     * @param reason 封禁原因
     * @return 封禁参数
     */
    private BanUserDTO buildBanInfo(String reason) {
        // 步骤1：写入原因
        BanUserDTO banInfo = new BanUserDTO();
        banInfo.setReason(reason);
        return banInfo;
    }

    /**
     * 构造后台用户列表查询参数。
     *
     * @param keyword 关键字前缀
     * @param status  账号状态筛选，null 表示不筛选
     * @param size    每页条数
     * @return 查询参数
     */
    private AdminUserQueryDTO buildUserQuery(String keyword, Integer status, int size) {
        // 步骤1：写入关键字、状态与条数
        AdminUserQueryDTO queryInfo = new AdminUserQueryDTO();
        queryInfo.setKeyword(keyword);
        queryInfo.setStatus(status);
        queryInfo.setSize(size);
        return queryInfo;
    }

    /**
     * 执行可能抛业务异常的动作并取错误码。
     *
     * @param action 被测动作
     * @return 错误码；未抛异常时返回 "0"
     */
    private String catchBusinessCode(Runnable action) {
        // 步骤1：执行并按异常取码，非业务异常继续上抛（不能让测试把 NPE 当成通过）
        try {
            action.run();
            return "0";
        } catch (BusinessException businessException) {
            return businessException.getCode();
        }
    }
}
