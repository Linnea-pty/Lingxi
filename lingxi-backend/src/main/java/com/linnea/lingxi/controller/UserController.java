package com.linnea.lingxi.controller;

import com.linnea.lingxi.common.web.Response;
import com.linnea.lingxi.dto.ListQueryDTO;
import com.linnea.lingxi.security.LoginUser;
import com.linnea.lingxi.service.UserService;
import com.linnea.lingxi.vo.FollowResultVO;
import com.linnea.lingxi.vo.FollowUserPageVO;
import com.linnea.lingxi.vo.UserProfileVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户主页与关注关系接口。
 *
 * <p>与 {@link UserCenterController} 分工：本类面向「看别人」，路径带 {userId}；
 * UserCenterController 面向「我自己」，路径固定 /users/me，两者不会互相覆盖。</p>
 */
@Tag(name = "用户与关注", description = "用户主页、关注、取关、关注列表、粉丝列表")
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    /** 用户业务服务。 */
    private final UserService userService;

    /**
     * 查询用户主页资料。
     *
     * @param userId    被查看的用户ID
     * @param loginUser 当前登录用户
     * @return 用户主页资料
     */
    @Operation(summary = "用户主页", description = "返回昵称、头像、关注数、粉丝数，以及当前登录用户是否已关注该用户")
    @GetMapping("/{userId}")
    public Response<UserProfileVO> queryUserProfile(@PathVariable("userId") Long userId,
                                                    @AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点
        long startMillis = System.currentTimeMillis();
        log.info("GET /api/v1/users/{} 开始, currentUserId={}", userId, loginUser.userId());
        // 步骤2：调用业务层查询主页资料
        UserProfileVO userProfile = userService.queryUserProfile(userId, loginUser.userId());
        // 步骤3：记录出口结果与耗时
        log.info("GET /api/v1/users/{} 完成, followerCount={}, followingCount={}, cost={} ms",
                userId, userProfile.getFollowerCount(), userProfile.getFollowingCount(),
                System.currentTimeMillis() - startMillis);
        return Response.success(userProfile);
    }

    /**
     * 关注某用户。
     *
     * @param userId    被关注用户ID
     * @param loginUser 当前登录用户
     * @return 关注结果
     */
    @Operation(summary = "关注用户", description = "幂等：已关注时重复调用仍返回成功，并刷新关注时间")
    @PostMapping("/{userId}/follow")
    public Response<FollowResultVO> followUser(@PathVariable("userId") Long userId,
                                               @AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点
        long startMillis = System.currentTimeMillis();
        log.info("POST /api/v1/users/{}/follow 开始, currentUserId={}", userId, loginUser.userId());
        // 步骤2：调用业务层建立关注关系
        FollowResultVO followResult = userService.followUser(userId, loginUser.userId());
        // 步骤3：记录出口结果与耗时
        log.info("POST /api/v1/users/{}/follow 完成, following={}, followerCount={}, cost={} ms",
                userId, followResult.getFollowing(), followResult.getFollowerCount(),
                System.currentTimeMillis() - startMillis);
        return Response.success(followResult);
    }

    /**
     * 取关某用户。
     *
     * @param userId    被取关用户ID
     * @param loginUser 当前登录用户
     * @return 取关结果
     */
    @Operation(summary = "取关用户", description = "幂等：原本未关注时重复调用仍返回成功")
    @DeleteMapping("/{userId}/follow")
    public Response<FollowResultVO> unfollowUser(@PathVariable("userId") Long userId,
                                                 @AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点
        long startMillis = System.currentTimeMillis();
        log.info("DELETE /api/v1/users/{}/follow 开始, currentUserId={}", userId, loginUser.userId());
        // 步骤2：调用业务层取消关注关系
        FollowResultVO followResult = userService.unfollowUser(userId, loginUser.userId());
        // 步骤3：记录出口结果与耗时
        log.info("DELETE /api/v1/users/{}/follow 完成, following={}, followerCount={}, cost={} ms",
                userId, followResult.getFollowing(), followResult.getFollowerCount(),
                System.currentTimeMillis() - startMillis);
        return Response.success(followResult);
    }

    /**
     * 查询某用户关注的人。
     *
     * @param userId        列表归属用户ID
     * @param listQueryInfo 分页参数
     * @param loginUser     当前登录用户
     * @return 关注列表分页结果
     */
    @Operation(summary = "关注列表", description = "按关注时间倒序，键集分页；每项带 isFollowing 表示我是否也关注了他")
    @GetMapping("/{userId}/following")
    public Response<FollowUserPageVO> queryFollowingPage(@PathVariable("userId") Long userId,
                                                         @Valid ListQueryDTO listQueryInfo,
                                                         @AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点
        long startMillis = System.currentTimeMillis();
        log.info("GET /api/v1/users/{}/following 开始, currentUserId={}, size={}",
                userId, loginUser.userId(), listQueryInfo.getSize());
        // 步骤2：调用业务层查询关注列表
        FollowUserPageVO followingPage = userService.queryFollowingPage(userId, loginUser.userId(), listQueryInfo);
        // 步骤3：记录出口结果与耗时
        log.info("GET /api/v1/users/{}/following 完成, itemCount={}, hasMore={}, cost={} ms",
                userId, followingPage.getUserList().size(), followingPage.getHasMore(),
                System.currentTimeMillis() - startMillis);
        return Response.success(followingPage);
    }

    /**
     * 查询某用户的粉丝。
     *
     * @param userId        列表归属用户ID
     * @param listQueryInfo 分页参数
     * @param loginUser     当前登录用户
     * @return 粉丝列表分页结果
     */
    @Operation(summary = "粉丝列表", description = "按关注时间倒序，键集分页；每项带 isFollowing 表示我是否关注了这位粉丝")
    @GetMapping("/{userId}/followers")
    public Response<FollowUserPageVO> queryFollowersPage(@PathVariable("userId") Long userId,
                                                         @Valid ListQueryDTO listQueryInfo,
                                                         @AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点
        long startMillis = System.currentTimeMillis();
        log.info("GET /api/v1/users/{}/followers 开始, currentUserId={}, size={}",
                userId, loginUser.userId(), listQueryInfo.getSize());
        // 步骤2：调用业务层查询粉丝列表
        FollowUserPageVO followerPage = userService.queryFollowersPage(userId, loginUser.userId(), listQueryInfo);
        // 步骤3：记录出口结果与耗时
        log.info("GET /api/v1/users/{}/followers 完成, itemCount={}, hasMore={}, cost={} ms",
                userId, followerPage.getUserList().size(), followerPage.getHasMore(),
                System.currentTimeMillis() - startMillis);
        return Response.success(followerPage);
    }
}
