package com.linnea.lingxi.controller;

import com.linnea.lingxi.common.web.Response;
import com.linnea.lingxi.dto.AdminUserQueryDTO;
import com.linnea.lingxi.dto.BanUserDTO;
import com.linnea.lingxi.security.LoginUser;
import com.linnea.lingxi.service.AdminService;
import com.linnea.lingxi.vo.AdminUserPageVO;
import com.linnea.lingxi.vo.AdminUserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员后台接口：账号治理（列表、封禁、解封）与内容治理（删任意动态/评论）。
 *
 * <p>权限：整类挂 {@code @PreAuthorize("hasRole('ADMIN')")}，方法上不再逐个重复——
 * 新增后台接口时天然继承这道闸，不会漏加注解；非管理员访问返回 403 与统一返回体。</p>
 *
 * <p>Agent（{@code ROLE_AGENT}）访问本类一律 403：API Key 认证只授予 Agent 角色，
 * 机器密钥不应具备封禁账号、删除他人内容的权力。</p>
 */
@Tag(name = "管理员后台", description = "账号治理与内容治理，仅 ADMIN 角色可访问")
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    /** 后台业务服务。 */
    private final AdminService adminService;

    /**
     * 分页查询用户列表。
     *
     * @param queryInfo 查询参数（游标、条数、关键字前缀、状态筛选）
     * @param loginUser 当前登录的管理员
     * @return 用户列表分页结果
     */
    @Operation(summary = "用户列表",
            description = "按注册时间倒序，键集分页；keyword 按用户名/昵称前缀匹配，status=0 只看封禁账号")
    @GetMapping("/users")
    public Response<AdminUserPageVO> queryUserPage(@Valid AdminUserQueryDTO queryInfo,
                                                   @AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点（关键字只记长度，避免把用户输入原样写进日志）
        long startMillis = System.currentTimeMillis();
        log.info("GET /api/v1/admin/users 开始, operatorUserId={}, status={}, size={}, hasKeyword={}",
                loginUser.userId(), queryInfo.getStatus(), queryInfo.getSize(),
                queryInfo.getKeyword() != null && !queryInfo.getKeyword().isEmpty());
        // 步骤2：调用业务层查询用户列表
        AdminUserPageVO userPage = adminService.queryUserPage(queryInfo);
        // 步骤3：记录出口结果与耗时
        log.info("GET /api/v1/admin/users 完成, operatorUserId={}, itemCount={}, hasMore={}, cost={} ms",
                loginUser.userId(), userPage.getUserList().size(), userPage.getHasMore(),
                System.currentTimeMillis() - startMillis);
        return Response.success(userPage);
    }

    /**
     * 封禁账号。
     *
     * @param userId    被封禁的用户ID
     * @param banInfo   封禁原因，可不传请求体
     * @param loginUser 当前登录的管理员
     * @return 封禁后的账号信息
     */
    @Operation(summary = "封禁账号", description = "封禁后该账号的旧令牌与 Agent 密钥立即失效；不能封自己、不能封管理员")
    @PostMapping("/users/{userId}/ban")
    public Response<AdminUserVO> banUser(@PathVariable("userId") Long userId,
                                         @RequestBody(required = false) @Valid BanUserDTO banInfo,
                                         @AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点（原因只记长度，避免把运营备注里的内容写进日志）
        long startMillis = System.currentTimeMillis();
        log.info("POST /api/v1/admin/users/{}/ban 开始, operatorUserId={}, reasonLength={}",
                userId, loginUser.userId(), banInfo == null || banInfo.getReason() == null
                        ? 0 : banInfo.getReason().length());
        // 步骤2：调用业务层封禁
        AdminUserVO bannedUserInfo = adminService.banUser(userId, banInfo, loginUser.userId());
        // 步骤3：记录出口结果与耗时
        log.info("POST /api/v1/admin/users/{}/ban 完成, operatorUserId={}, status={}, cost={} ms",
                userId, loginUser.userId(), bannedUserInfo.getStatus(), System.currentTimeMillis() - startMillis);
        return Response.success(bannedUserInfo);
    }

    /**
     * 解封账号。
     *
     * @param userId    被解封的用户ID
     * @param loginUser 当前登录的管理员
     * @return 解封后的账号信息
     */
    @Operation(summary = "解封账号", description = "解除封禁并清空封禁原因；该账号可重新登录")
    @PostMapping("/users/{userId}/unban")
    public Response<AdminUserVO> unbanUser(@PathVariable("userId") Long userId,
                                           @AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点
        long startMillis = System.currentTimeMillis();
        log.info("POST /api/v1/admin/users/{}/unban 开始, operatorUserId={}", userId, loginUser.userId());
        // 步骤2：调用业务层解封
        AdminUserVO unbannedUserInfo = adminService.unbanUser(userId, loginUser.userId());
        // 步骤3：记录出口结果与耗时
        log.info("POST /api/v1/admin/users/{}/unban 完成, operatorUserId={}, status={}, cost={} ms",
                userId, loginUser.userId(), unbannedUserInfo.getStatus(), System.currentTimeMillis() - startMillis);
        return Response.success(unbannedUserInfo);
    }

    /**
     * 删除任意动态。
     *
     * @param postId    动态ID
     * @param loginUser 当前登录的管理员
     * @return 无业务数据的成功响应
     */
    @Operation(summary = "删除动态（后台）", description = "逻辑删除，同时删除其下全部评论；不校验作者归属")
    @DeleteMapping("/posts/{postId}")
    public Response<Void> removePost(@PathVariable("postId") Long postId,
                                     @AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点
        long startMillis = System.currentTimeMillis();
        log.info("DELETE /api/v1/admin/posts/{} 开始, operatorUserId={}", postId, loginUser.userId());
        // 步骤2：调用业务层删除
        adminService.removePost(postId, loginUser.userId());
        // 步骤3：记录出口结果与耗时
        log.info("DELETE /api/v1/admin/posts/{} 完成, operatorUserId={}, cost={} ms",
                postId, loginUser.userId(), System.currentTimeMillis() - startMillis);
        return Response.success();
    }

    /**
     * 删除任意评论。
     *
     * @param commentId 评论ID
     * @param loginUser 当前登录的管理员
     * @return 无业务数据的成功响应
     */
    @Operation(summary = "删除评论（后台）", description = "逻辑删除，连同其下全部回复一起删除；不校验作者归属")
    @DeleteMapping("/comments/{commentId}")
    public Response<Void> removeComment(@PathVariable("commentId") Long commentId,
                                        @AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点
        long startMillis = System.currentTimeMillis();
        log.info("DELETE /api/v1/admin/comments/{} 开始, operatorUserId={}", commentId, loginUser.userId());
        // 步骤2：调用业务层删除
        adminService.removeComment(commentId, loginUser.userId());
        // 步骤3：记录出口结果与耗时
        log.info("DELETE /api/v1/admin/comments/{} 完成, operatorUserId={}, cost={} ms",
                commentId, loginUser.userId(), System.currentTimeMillis() - startMillis);
        return Response.success();
    }
}
