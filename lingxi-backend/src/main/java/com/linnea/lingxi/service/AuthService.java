package com.linnea.lingxi.service;

import com.linnea.lingxi.dto.LoginDTO;
import com.linnea.lingxi.dto.RegisterDTO;
import com.linnea.lingxi.vo.ApiKeyVO;
import com.linnea.lingxi.vo.LoginVO;
import com.linnea.lingxi.vo.UserVO;

/**
 * 认证业务服务：注册、登录与当前登录用户查询。
 *
 * <p>接口只描述业务能力，令牌签发、密码哈希等实现细节由实现类承担；
 * 返回值为 VO，Entity 不跨出服务层。</p>
 */
public interface AuthService {

    /**
     * 注册账号并直接签发访问令牌（注册即登录）。
     *
     * @param registerInfo 注册请求参数，已通过 Bean Validation 校验
     * @return 访问令牌与用户信息
     */
    LoginVO register(RegisterDTO registerInfo);

    /**
     * 校验用户名与密码并签发访问令牌。
     *
     * @param loginInfo 登录请求参数，已通过 Bean Validation 校验
     * @return 访问令牌与用户信息
     */
    LoginVO login(LoginDTO loginInfo);

    /**
     * 用刷新令牌换发一套新令牌（旧刷新令牌同时作废）。
     *
     * @param refreshToken 登录时下发的刷新令牌
     * @return 新的访问令牌、刷新令牌与用户信息
     */
    LoginVO refreshToken(String refreshToken);

    /**
     * 登出：吊销当前访问令牌；若提交了刷新令牌，一并吊销。
     *
     * <p>重复登出不报错（幂等），客户端不必关心是否已经登出过。</p>
     *
     * @param userId     当前登录用户ID，来自认证上下文而非请求参数
     * @param tokenId    当前访问令牌的 jti，来自认证上下文
     * @param refreshToken 刷新令牌，选填，可为 null
     */
    void logout(Long userId, String tokenId, String refreshToken);

    /**
     * 查询当前登录用户信息。
     *
     * @param userId 当前登录用户ID，来自认证上下文而非请求参数
     * @return 用户对外信息
     */
    UserVO queryCurrentUserInfo(Long userId);

    /**
     * 重新签发 Agent API Key，旧密钥立即失效。
     *
     * <p>密钥明文只在返回值中出现一次，库内仅保存摘要。</p>
     *
     * @param userId 当前登录用户ID，来自认证上下文而非请求参数
     * @return 新密钥明文与使用方式
     */
    ApiKeyVO rotateApiKey(Long userId);
}
