package com.linnea.lingxi.service.impl;

import com.linnea.lingxi.common.exception.BusinessException;
import com.linnea.lingxi.common.exception.ErrorCode;
import com.linnea.lingxi.dto.ListQueryDTO;
import com.linnea.lingxi.dto.RegisterDTO;
import com.linnea.lingxi.dto.UpdateProfileDTO;
import com.linnea.lingxi.service.AuthService;
import com.linnea.lingxi.service.UserService;
import com.linnea.lingxi.support.AbstractIntegrationTest;
import com.linnea.lingxi.vo.FollowResultVO;
import com.linnea.lingxi.vo.FollowUserPageVO;
import com.linnea.lingxi.vo.LoginVO;
import com.linnea.lingxi.vo.UserProfileVO;
import com.linnea.lingxi.vo.UserVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用户资料、头像上传与关注关系的集成测试，跑在真实 MySQL 上。
 *
 * <p>WHY 必须真实数据库：核心校验依赖两处方言特性——uk_user_follow 唯一键上的
 * {@code ON DUPLICATE KEY UPDATE}（关注幂等靠它）、以及「同一秒内多次关注」时
 * {@code (follow_time, id)} 游标能否不重不漏地翻完两页。Mockito 替身既不会执行唯一键，
 * 也不会暴露游标在时间并列时的缺陷。</p>
 */
@Transactional
class UserFollowIT extends AbstractIntegrationTest {

    /** 测试账号密码，满足 8~64 位要求。 */
    private static final String PASSWORD = "lingxi@2026";

    /** 用户名序号：同一测试内多次注册需要不同用户名。 */
    private static final AtomicInteger USERNAME_SEQ = new AtomicInteger();

    /** 头像存储根目录，与 AbstractIntegrationTest 注入的 app.upload.avatar-dir 保持一致。 */
    private static final Path AVATAR_STORAGE_ROOT = Paths.get("target/test-uploads");

    /** 认证业务服务：注册即登录，用于拿用户ID。 */
    @Autowired
    private AuthService authService;

    /** 用户业务服务。 */
    @Autowired
    private UserService userService;

    @Test
    @DisplayName("修改昵称：首尾空白被裁掉后落库")
    void updateProfile_trimsNickname() {
        // 前置：注册一个账号
        Long userId = registerUser();
        // 步骤1：提交带首尾空白的昵称
        UserVO updatedUserInfo = userService.updateProfile(userId, buildProfileInfo("  新昵称  "));
        // 步骤2：库里存的是裁剪后的值
        assertThat(updatedUserInfo.getNickname()).isEqualTo("新昵称");
    }

    @Test
    @DisplayName("修改昵称：裁掉空白后不足 2 字符被拒，返回 COMMON_PARAM_ERROR")
    void updateProfile_whenTooShortAfterTrim_throwsParamError() {
        // 前置
        Long userId = registerUser();
        // 执行 + 断言：" a " 能通过 @Size(min=2)，但裁完只剩 1 个字符
        assertThatThrownBy(() -> userService.updateProfile(userId, buildProfileInfo(" a ")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.COMMON_PARAM_ERROR.getCode());
    }

    @Test
    @DisplayName("修改昵称：账号不存在返回 USER_NOT_FOUND")
    void updateProfile_whenUserMissing_throwsNotFound() {
        // 执行 + 断言
        assertThatThrownBy(() -> userService.updateProfile(999_999_999L, buildProfileInfo("随便改")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.USER_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("关注：重复关注幂等，粉丝数与关注数各自正确")
    void followUser_isIdempotent() {
        // 前置：两个账号，A 关注 B
        Long followerUserId = registerUser();
        Long followedUserId = registerUser();
        FollowResultVO firstResult = userService.followUser(followedUserId, followerUserId);
        FollowResultVO secondResult = userService.followUser(followedUserId, followerUserId);

        // 步骤1：两次都返回已关注，粉丝数不因重复调用翻倍
        assertThat(firstResult.getFollowing()).isTrue();
        assertThat(secondResult.getFollowing()).isTrue();
        assertThat(secondResult.getFollowerCount()).isEqualTo(1L);

        // 步骤2：B 的粉丝数 1、关注数 0；A 的关注数 1、粉丝数 0
        assertThat(userService.queryUserProfile(followedUserId, followerUserId).getFollowerCount()).isEqualTo(1L);
        assertThat(userService.queryUserProfile(followedUserId, followerUserId).getFollowingCount()).isZero();
        assertThat(userService.queryUserProfile(followerUserId, followerUserId).getFollowingCount()).isEqualTo(1L);
        assertThat(userService.queryUserProfile(followerUserId, followerUserId).getFollowerCount()).isZero();
    }

    @Test
    @DisplayName("关注：不能关注自己")
    void followUser_whenSelf_throwsFollowSelf() {
        // 前置
        Long userId = registerUser();
        // 执行 + 断言
        assertThatThrownBy(() -> userService.followUser(userId, userId))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.USER_FOLLOW_SELF.getCode());
    }

    @Test
    @DisplayName("关注：目标用户不存在返回 USER_NOT_FOUND")
    void followUser_whenTargetMissing_throwsNotFound() {
        // 前置
        Long userId = registerUser();
        // 执行 + 断言
        assertThatThrownBy(() -> userService.followUser(999_999_999L, userId))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.USER_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("取关：计数归零，重复取关幂等不报错")
    void unfollowUser_isIdempotent() {
        // 前置：A 关注 B
        Long followerUserId = registerUser();
        Long followedUserId = registerUser();
        userService.followUser(followedUserId, followerUserId);

        // 步骤1：第一次取关，粉丝数归零
        FollowResultVO firstResult = userService.unfollowUser(followedUserId, followerUserId);
        assertThat(firstResult.getFollowing()).isFalse();
        assertThat(firstResult.getFollowerCount()).isZero();

        // 步骤2：重复取关仍成功（幂等），计数不变成负数
        FollowResultVO secondResult = userService.unfollowUser(followedUserId, followerUserId);
        assertThat(secondResult.getFollowing()).isFalse();
        assertThat(secondResult.getFollowerCount()).isZero();

        // 步骤3：取关后再次关注能重新建立关系（验证状态位方案不会撞唯一键）
        FollowResultVO reFollowResult = userService.followUser(followedUserId, followerUserId);
        assertThat(reFollowResult.getFollowing()).isTrue();
        assertThat(reFollowResult.getFollowerCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("关注列表：size=1 时靠游标翻完两页，且回填我是否已关注")
    void queryFollowingPage_paginatesByCursor() {
        // 前置：A 关注 B 与 C；B 也关注 A（用于验证 isFollowing 回填）
        Long ownerUserId = registerUser();
        Long firstFollowedUserId = registerUser();
        Long secondFollowedUserId = registerUser();
        userService.followUser(firstFollowedUserId, ownerUserId);
        userService.followUser(secondFollowedUserId, ownerUserId);
        userService.followUser(ownerUserId, firstFollowedUserId);

        // 步骤1：以 B 的视角看 A 的关注列表，第一页 1 条且还有下一页
        FollowUserPageVO firstPage = userService.queryFollowingPage(ownerUserId, firstFollowedUserId,
                buildListQueryInfo(null, 1));
        assertThat(firstPage.getUserList()).hasSize(1);
        assertThat(firstPage.getHasMore()).isTrue();
        assertThat(firstPage.getNextCursor()).isNotBlank();

        // 步骤2：第二页拿到剩下 1 条，没有更多
        FollowUserPageVO secondPage = userService.queryFollowingPage(ownerUserId, firstFollowedUserId,
                buildListQueryInfo(firstPage.getNextCursor(), 1));
        assertThat(secondPage.getUserList()).hasSize(1);
        assertThat(secondPage.getHasMore()).isFalse();
        assertThat(secondPage.getNextCursor()).isNull();

        // 步骤3：两页合起来恰好是 B、C 两个人，不重不漏
        assertThat(secondPage.getUserList().get(0).getUserId())
                .isNotEqualTo(firstPage.getUserList().get(0).getUserId());

        // 步骤4：isFollowing 按「B 已关注谁」回填——B 关注了 A，没关注 C
        FollowUserPageVO fullPage = userService.queryFollowingPage(ownerUserId, firstFollowedUserId,
                buildListQueryInfo(null, 10));
        assertThat(fullPage.getUserList()).hasSize(2);
        assertThat(fullPage.getUserList())
                .allSatisfy(followUserInfo -> assertThat(followUserInfo.getIsFollowing())
                        .isEqualTo(followUserInfo.getUserId().equals(ownerUserId)));
    }

    @Test
    @DisplayName("粉丝列表：返回粉丝资料与关注时间，计数与列表口径一致")
    void queryFollowersPage_returnsFans() {
        // 前置：B、C 都关注 A
        Long ownerUserId = registerUser();
        Long firstFanUserId = registerUser();
        Long secondFanUserId = registerUser();
        userService.followUser(ownerUserId, firstFanUserId);
        userService.followUser(ownerUserId, secondFanUserId);

        // 步骤1：A 的粉丝列表 2 条，均带关注时间
        FollowUserPageVO followerPage = userService.queryFollowersPage(ownerUserId, ownerUserId,
                buildListQueryInfo(null, 10));
        assertThat(followerPage.getUserList()).hasSize(2);
        assertThat(followerPage.getHasMore()).isFalse();
        assertThat(followerPage.getUserList())
                .allSatisfy(followUserInfo -> assertThat(followUserInfo.getFollowedAt()).isNotNull());

        // 步骤2：列表里的用户ID集合与两位粉丝一致，且与自己主页的粉丝数对得上
        assertThat(followerPage.getUserList()).extracting(followUserInfo -> followUserInfo.getUserId())
                .containsExactlyInAnyOrder(firstFanUserId, secondFanUserId);
        assertThat(userService.queryUserProfile(ownerUserId, ownerUserId).getFollowerCount())
                .isEqualTo(followerPage.getUserList().size());
    }

    @Test
    @DisplayName("用户主页：看自己的主页时 isFollowing 恒为 false")
    void queryUserProfile_whenSelf_isFollowingFalse() {
        // 前置
        Long userId = registerUser();
        // 执行 + 断言
        UserProfileVO userProfile = userService.queryUserProfile(userId, userId);
        assertThat(userProfile.getIsFollowing()).isFalse();
    }

    @Test
    @DisplayName("上传头像：返回 /uploads/avatar/{userId}/ 前缀的地址，且文件真实落盘")
    void updateAvatar_storesFileAndReturnsUrl() {
        // 前置：注册账号并准备一张最小 PNG（判定只看文件头，不需要真实像素数据）
        Long userId = registerUser();
        MockMultipartFile avatarFile = new MockMultipartFile("file", "avatar.png", "image/png", buildPngBytes());

        // 步骤1：上传头像
        UserVO updatedUserInfo = userService.updateAvatar(userId, avatarFile);

        // 步骤2：URL 形态正确
        assertThat(updatedUserInfo.getAvatarUrl()).startsWith("/uploads/avatar/" + userId + "/");
        assertThat(updatedUserInfo.getAvatarUrl()).endsWith(".png");

        // 步骤3：文件确实落在存储根目录内，且内容非空
        Path storedFilePath = AVATAR_STORAGE_ROOT.resolve(updatedUserInfo.getAvatarUrl().substring("/uploads/".length()));
        assertThat(Files.exists(storedFilePath)).isTrue();
        assertThat(readFileSizeQuietly(storedFilePath)).isGreaterThan(0L);
    }

    @Test
    @DisplayName("上传头像：非图片内容被拒（不信任扩展名与 Content-Type）")
    void updateAvatar_whenNotImage_throwsTypeNotAllowed() {
        // 前置：文件名与 Content-Type 都伪装成 png，内容是纯文本
        Long userId = registerUser();
        MockMultipartFile fakeAvatarFile = new MockMultipartFile("file", "avatar.png", "image/png",
                "not-an-image".getBytes());

        // 执行 + 断言：按文件头判定后拒绝
        assertThatThrownBy(() -> userService.updateAvatar(userId, fakeAvatarFile))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.FILE_TYPE_NOT_ALLOWED.getCode());
    }

    @Test
    @DisplayName("上传头像：空文件被拒")
    void updateAvatar_whenEmpty_throwsFileEmpty() {
        // 前置
        Long userId = registerUser();
        MockMultipartFile emptyAvatarFile = new MockMultipartFile("file", "avatar.png", "image/png", new byte[0]);

        // 执行 + 断言
        assertThatThrownBy(() -> userService.updateAvatar(userId, emptyAvatarFile))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.FILE_UPLOAD_EMPTY.getCode());
    }

    /**
     * 注册一个随机用户名的新用户。
     *
     * @return 新用户ID
     */
    private Long registerUser() {
        // 步骤1：用户名加自增序号，保证同一测试内多次注册不撞唯一键
        String username = "follow_" + USERNAME_SEQ.incrementAndGet() + "_" + System.nanoTime() % 100_000L;
        RegisterDTO registerInfo = new RegisterDTO();
        registerInfo.setUsername(username);
        registerInfo.setPassword(PASSWORD);
        registerInfo.setNickname(username);
        // 步骤2：注册即登录，直接取用户ID
        LoginVO loginResult = authService.register(registerInfo);
        return loginResult.getUserInfo().getUserId();
    }

    /**
     * 构造修改资料请求参数。
     *
     * @param nickname 昵称
     * @return 修改资料请求参数
     */
    private UpdateProfileDTO buildProfileInfo(String nickname) {
        // 步骤1：写入昵称
        UpdateProfileDTO updateProfileInfo = new UpdateProfileDTO();
        updateProfileInfo.setNickname(nickname);
        return updateProfileInfo;
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
     * 构造一张最小 PNG 字节序列。
     *
     * <p>AvatarStorage 只读前 12 字节判定类型，因此 8 字节 PNG 签名 + 填充即可；
     * 不引入真实图片资源，避免测试依赖二进制文件。</p>
     *
     * @return PNG 字节序列
     */
    private byte[] buildPngBytes() {
        // 步骤1：PNG 固定签名
        byte[] pngBytes = new byte[32];
        pngBytes[0] = (byte) 0x89;
        pngBytes[1] = 0x50;
        pngBytes[2] = 0x4E;
        pngBytes[3] = 0x47;
        pngBytes[4] = 0x0D;
        pngBytes[5] = 0x0A;
        pngBytes[6] = 0x1A;
        pngBytes[7] = 0x0A;
        // 步骤2：填充几个非零字节，确保文件大小断言不为 0
        for (int index = 8; index < pngBytes.length; index++) {
            pngBytes[index] = (byte) index;
        }
        return pngBytes;
    }

    /**
     * 读取文件大小，读取失败按 0 处理（仅用于断言）。
     *
     * @param filePath 文件路径
     * @return 文件字节数
     */
    private long readFileSizeQuietly(Path filePath) {
        // 步骤1：读取属性，异常返回 0 让断言自然失败
        try {
            return Files.size(filePath);
        } catch (IOException sizeException) {
            return 0L;
        }
    }
}
