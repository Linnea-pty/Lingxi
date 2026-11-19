package com.linnea.lingxi.controller;

import com.linnea.lingxi.common.web.Response;
import com.linnea.lingxi.dto.LoginDTO;
import com.linnea.lingxi.dto.LogoutDTO;
import com.linnea.lingxi.dto.RefreshTokenDTO;
import com.linnea.lingxi.dto.RegisterDTO;
import com.linnea.lingxi.security.LoginUser;
import com.linnea.lingxi.service.AuthService;
import com.linnea.lingxi.vo.ApiKeyVO;
import com.linnea.lingxi.vo.LoginVO;
import com.linnea.lingxi.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：注册、登录、令牌换发、登出与当前登录用户查询。
 *
 * <p>Controller 只做参数接收、结果包装与入口出日志，不含业务判断；
 * 注册、登录与令牌换发在安全白名单内匿名可访问（换发凭刷新令牌而非访问令牌），
 * 其余接口需携带访问令牌。</p>
 */
@Tag(name = "认证", description = "注册、登录、令牌换发、登出与当前登录用户信息")
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    /** 认证业务服务。 */
    private final AuthService authService;

    /**
     * 注册账号并直接登录。
     *
     * @param registerInfo 注册请求参数
     * @return 访问令牌与用户信息
     */
    @Operation(summary = "注册并登录", description = "创建账号（用户名全局唯一且注销后不释放），成功后直接返回访问令牌")
    @PostMapping("/register")
    public Response<LoginVO> register(@Valid @RequestBody RegisterDTO registerInfo) {
        // 步骤1：记录请求入口与耗时起点，入参只打印用户名，密码不落日志
        long startMillis = System.currentTimeMillis();
        log.info("POST /api/v1/auth/register 开始, username={}", registerInfo.getUsername());
        // 步骤2：调用业务层
        LoginVO loginResult = authService.register(registerInfo);
        // 步骤3：记录出口结果与耗时，令牌不写日志
        Long userId = loginResult.getUserInfo().getUserId();
        log.info("POST /api/v1/auth/register 完成, userId={}, cost={} ms",
                userId, System.currentTimeMillis() - startMillis);
        return Response.success(loginResult);
    }

    /**
     * 用户名密码登录。
     *
     * @param loginInfo 登录请求参数
     * @return 访问令牌与用户信息
     */
    @Operation(summary = "登录", description = "校验用户名与密码，成功后返回访问令牌；账号不存在与密码错误返回同一错误码")
    @PostMapping("/login")
    public Response<LoginVO> login(@Valid @RequestBody LoginDTO loginInfo) {
        // 步骤1：记录请求入口与耗时起点，密码不写日志
        long startMillis = System.currentTimeMillis();
        log.info("POST /api/v1/auth/login 开始, username={}", loginInfo.getUsername());
        // 步骤2：调用业务层
        LoginVO loginResult = authService.login(loginInfo);
        // 步骤3：记录出口结果与耗时
        Long userId = loginResult.getUserInfo().getUserId();
        log.info("POST /api/v1/auth/login 完成, userId={}, cost={} ms",
                userId, System.currentTimeMillis() - startMillis);
        return Response.success(loginResult);
    }

    /**
     * 用刷新令牌换发一套新令牌。
     *
     * @param refreshTokenInfo 刷新令牌请求参数
     * @return 新的访问令牌、刷新令牌与用户信息
     */
    @Operation(summary = "换发令牌",
            description = "凭刷新令牌换发新的访问令牌与刷新令牌；每次换发都会作废旧刷新令牌，旧令牌再次使用会被拒绝")
    @PostMapping("/refresh")
    public Response<LoginVO> refresh(@Valid @RequestBody RefreshTokenDTO refreshTokenInfo) {
        // 步骤1：记录请求入口与耗时起点，刷新令牌本身不写日志
        long startMillis = System.currentTimeMillis();
        log.info("POST /api/v1/auth/refresh 开始");
        // 步骤2：调用业务层换发令牌
        LoginVO loginResult = authService.refreshToken(refreshTokenInfo.getRefreshToken());
        // 步骤3：记录出口结果与耗时，令牌不写日志
        Long userId = loginResult.getUserInfo().getUserId();
        log.info("POST /api/v1/auth/refresh 完成, userId={}, cost={} ms",
                userId, System.currentTimeMillis() - startMillis);
        return Response.success(loginResult);
    }

    /**
     * 登出：吊销当前访问令牌，可选一并吊销刷新令牌。
     *
     * @param loginUser    当前登录用户，由 JWT 过滤器写入 SecurityContext 后注入
     * @param logoutInfo   登出请求参数，选填刷新令牌
     * @return 无业务数据的成功响应
     */
    @Operation(summary = "登出",
            description = "吊销当前访问令牌使其在有效期内立即失效；请求体可附刷新令牌，一并作废后无法再换发新令牌")
    @PostMapping("/logout")
    public Response<Void> logout(@AuthenticationPrincipal LoginUser loginUser,
                                 @RequestBody(required = false) LogoutDTO logoutInfo) {
        // 步骤1：记录请求入口与耗时起点，令牌不写日志
        long startMillis = System.currentTimeMillis();
        boolean refreshTokenSubmitted = logoutInfo != null && logoutInfo.getRefreshToken() != null;
        log.info("POST /api/v1/auth/logout 开始, userId={}, 是否提交刷新令牌={}",
                loginUser.userId(), refreshTokenSubmitted);
        // 步骤2：调用业务层吊销令牌，未提交刷新令牌时传 null
        authService.logout(loginUser.userId(), loginUser.tokenId(),
                refreshTokenSubmitted ? logoutInfo.getRefreshToken() : null);
        // 步骤3：记录出口结果与耗时
        log.info("POST /api/v1/auth/logout 完成, userId={}, cost={} ms",
                loginUser.userId(), System.currentTimeMillis() - startMillis);
        return Response.success(null);
    }

    /**
     * 查询当前登录用户信息。
     *
     * @param loginUser 当前登录用户，由 JWT 过滤器写入 SecurityContext 后注入
     * @return 用户对外信息
     */
    @Operation(summary = "查询当前登录用户信息", description = "依据请求头 Authorization 中的访问令牌返回当前用户资料")
    @GetMapping("/current-user-info")
    public Response<UserVO> getCurrentUserInfo(@AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点
        long startMillis = System.currentTimeMillis();
        log.info("GET /api/v1/auth/current-user-info 开始, userId={}", loginUser.userId());
        // 步骤2：调用业务层查询
        UserVO currentUserInfo = authService.queryCurrentUserInfo(loginUser.userId());
        // 步骤3：记录出口结果与耗时
        log.info("GET /api/v1/auth/current-user-info 完成, userId={}, cost={} ms",
                currentUserInfo.getUserId(), System.currentTimeMillis() - startMillis);
        return Response.success(currentUserInfo);
    }

    /**
     * 重新签发 Agent API Key。
     *
     * @param loginUser 当前登录用户，由认证过滤器写入 SecurityContext 后注入
     * @return 新密钥明文（仅本次返回）与使用方式
     */
    @Operation(summary = "重置 Agent API Key",
            description = "为当前账号生成新的 Agent 密钥并在本次响应中返回一次；旧密钥立即失效，库内只存摘要")
    @PostMapping("/api-key/rotate")
    public Response<ApiKeyVO> rotateApiKey(@AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点
        long startMillis = System.currentTimeMillis();
        log.info("POST /api/v1/auth/api-key/rotate 开始, userId={}", loginUser.userId());
        // 步骤2：调用业务层签发新密钥
        ApiKeyVO apiKeyInfo = authService.rotateApiKey(loginUser.userId());
        // 步骤3：记录出口结果与耗时，密钥明文不写日志
        log.info("POST /api/v1/auth/api-key/rotate 完成, userId={}, cost={} ms",
                loginUser.userId(), System.currentTimeMillis() - startMillis);
        return Response.success(apiKeyInfo);
    }
}
