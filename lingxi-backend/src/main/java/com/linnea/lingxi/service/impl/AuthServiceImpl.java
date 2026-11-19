package com.linnea.lingxi.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.linnea.lingxi.common.enums.UserStatus;
import com.linnea.lingxi.common.exception.BusinessException;
import com.linnea.lingxi.common.exception.ErrorCode;
import com.linnea.lingxi.dto.LoginDTO;
import com.linnea.lingxi.dto.RegisterDTO;
import com.linnea.lingxi.entity.User;
import com.linnea.lingxi.mapper.UserMapper;
import com.linnea.lingxi.security.ApiKeyCodec;
import com.linnea.lingxi.security.IssuedToken;
import com.linnea.lingxi.security.JwtTokenProvider;
import com.linnea.lingxi.security.TokenStore;
import com.linnea.lingxi.service.AuthService;
import com.linnea.lingxi.vo.ApiKeyVO;
import com.linnea.lingxi.vo.LoginVO;
import com.linnea.lingxi.vo.UserVO;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 认证业务实现：密码 BCrypt 哈希、用户名唯一性由唯一索引兜底、登录失败不区分账号或密码错误。
 *
 * <p>用户名比较区分大小写：uk_username 建在列级 utf8mb4_0900_as_cs 上，Alice 与 alice 是两个账号，
 * 因此代码侧只做 trim、绝不做大小写归一（一旦归一，DDL 的敏感比较就被绕过了）。</p>
 *
 * <p>并发安全：注册的「查重 + 插入」存在竞态，最终一致性依赖 uk_username，
 * 撞键时转换为同一业务错误码，不向前端暴露数据库异常。</p>
 *
 * <p>令牌策略：登录/注册同时签发访问令牌与刷新令牌，刷新令牌的 jti 登记到 Redis 后才可换发；
 * 换发即轮换（旧刷新令牌被原子取走），登出则吊销当前访问令牌并删除刷新令牌登记。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /** Agent 密钥使用提示：把请求头名称写进返回值，避免调用方猜认证方式。 */
    private static final String API_KEY_USAGE_HINT =
            "请求头 X-Api-Key 携带该密钥调用 /api/v1/posts，代发动态自动置顶且来源记为 agent";

    /** 日志中摘要的前缀长度：摘要不可逆，取前缀仅用于定位「哪次签发」，不泄露密钥本身。 */
    private static final int DIGEST_LOG_PREFIX_LENGTH = 12;

    /** 用户数据访问。 */
    private final UserMapper userMapper;

    /** 密码编码器：注册哈希、登录比对。 */
    private final PasswordEncoder passwordEncoder;

    /** 访问令牌签发组件。 */
    private final JwtTokenProvider jwtTokenProvider;

    /** 令牌服务端状态存储：刷新令牌登记与访问令牌吊销。 */
    private final TokenStore tokenStore;

    /**
     * 注册账号并直接签发访问令牌。
     *
     * @param registerInfo 注册请求参数
     * @return 访问令牌与用户信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginVO register(RegisterDTO registerInfo) {
        // 步骤1：提取用户名并去除首尾空白，大小写保持原样不做归一。
        // 注册侧的空白实际由 RegisterDTO 的 @Pattern 先拦下（COMMON_PARAM_ERROR），此处 trim 是兜底；
        // 真正依赖 trim 的是登录侧（LoginDTO 只校验非空，允许 " linxi" 登录到 linxi）
        String username = registerInfo.getUsername().trim();
        log.info("注册开始, username={}", username);
        // 步骤2：唯一性预校验，只能拦住未注销的同名账号；已注销账号被逻辑删除条件过滤掉，
        // 「用户名不释放」由步骤4 的唯一索引冲突兜底
        Long existedCount = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (existedCount != null && existedCount > 0) {
            log.warn("注册被拒绝, 用户名已存在, username={}", username);
            throw new BusinessException(ErrorCode.USER_NAME_DUPLICATED);
        }
        // 步骤3：组装实体，密码做 BCrypt 哈希，昵称缺省取用户名
        User newUser = new User();
        newUser.setUsername(username);
        newUser.setPasswordHash(passwordEncoder.encode(registerInfo.getPassword()));
        newUser.setNickname(StringUtils.hasText(registerInfo.getNickname())
                ? registerInfo.getNickname().trim() : username);
        // 步骤4：落库，唯一索引兜底并发注册
        try {
            userMapper.insert(newUser);
        } catch (DuplicateKeyException duplicateKeyException) {
            log.warn("注册失败, 用户名唯一索引冲突, username={}", username, duplicateKeyException);
            throw new BusinessException(ErrorCode.USER_NAME_DUPLICATED);
        }
        log.info("注册成功, userId={}, username={}", newUser.getId(), username);
        // 步骤5：注册即登录，签发访问令牌返回
        return buildLoginVO(newUser);
    }

    /**
     * 校验用户名与密码并签发访问令牌。
     *
     * @param loginInfo 登录请求参数
     * @return 访问令牌与用户信息
     */
    @Override
    public LoginVO login(LoginDTO loginInfo) {
        // 步骤1：提取用户名并去除首尾空白
        String username = loginInfo.getUsername().trim();
        log.info("登录开始, username={}", username);
        // 步骤2：按用户名查询，逻辑删除自动过滤已注销账号
        User existedUser = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        // 步骤3：账号不存在与密码错误返回同一错误码，避免用户名枚举
        if (existedUser == null) {
            log.warn("登录被拒绝, 账号不存在, username={}", username);
            throw new BusinessException(ErrorCode.USER_LOGIN_FAILED);
        }
        // 步骤4：比对密码哈希，明文密码不落库、不打日志
        if (!passwordEncoder.matches(loginInfo.getPassword(), existedUser.getPasswordHash())) {
            log.warn("登录被拒绝, 密码不匹配, userId={}", existedUser.getId());
            throw new BusinessException(ErrorCode.USER_LOGIN_FAILED);
        }
        // 步骤4.1：密码正确之后才提示封禁。
        // WHY 不放在密码校验之前：那样攻击者拿任意密码就能用「已封禁」确认某个用户名存在，等于送了一个用户名枚举接口。
        if (UserStatus.isBanned(existedUser.getStatus())) {
            log.warn("登录被拒绝, 账号已被封禁, userId={}", existedUser.getId());
            throw new BusinessException(ErrorCode.USER_BANNED);
        }
        // 步骤5：签发令牌并组装登录结果
        LoginVO loginResult = buildLoginVO(existedUser);
        log.info("登录成功, userId={}", existedUser.getId());
        return loginResult;
    }

    /**
     * 用刷新令牌换发一套新令牌（旧刷新令牌同时作废）。
     *
     * <p>不加事务：这里唯一的写操作是 Redis 里的刷新令牌轮换，数据库只读；
     * 包进事务反而会让「先消费旧令牌、后查库失败」多一层无意义的回滚语义。</p>
     *
     * @param refreshToken 登录时下发的刷新令牌
     * @return 新的访问令牌、刷新令牌与用户信息
     */
    @Override
    public LoginVO refreshToken(String refreshToken) {
        // 步骤1：空白入参直接按无效刷新令牌拒绝，不进入解析
        if (!StringUtils.hasText(refreshToken)) {
            log.warn("刷新令牌换发被拒绝, 刷新令牌为空");
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }
        // 步骤2：校验签名、有效期与 type=refresh，过期与非法分别给不同日志便于排查
        Claims claims;
        try {
            claims = jwtTokenProvider.parseRefreshToken(refreshToken.trim());
        } catch (ExpiredJwtException expiredJwtException) {
            log.warn("刷新令牌换发被拒绝, 刷新令牌已过期", expiredJwtException);
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, "刷新令牌已过期，请重新登录");
        } catch (JwtException | IllegalArgumentException jwtException) {
            log.warn("刷新令牌换发被拒绝, 令牌校验失败, reason={}", jwtException.getMessage());
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }
        // 步骤3：解析出用户ID与 jti，任一缺失都说明不是本系统签发的令牌
        Long userId;
        String tokenId = claims.getId();
        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (NumberFormatException numberFormatException) {
            log.warn("刷新令牌换发被拒绝, subject 不是合法用户ID, subject={}", claims.getSubject());
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }
        // 步骤4：原子消费旧刷新令牌，取不到即视为「已用过/已登出/伪造」，重放由此被拦下
        if (!StringUtils.hasText(tokenId) || !tokenStore.consumeRefreshToken(tokenId, userId)) {
            log.warn("刷新令牌换发被拒绝, 令牌未登记或已被使用, userId={}, tokenId={}", userId, tokenId);
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }
        // 步骤5：回查账号，已注销用户在换发环节同样立即失效
        User existedUser = userMapper.selectById(userId);
        if (existedUser == null) {
            log.warn("刷新令牌换发被拒绝, 账号不存在或已注销, userId={}", userId);
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        // 步骤5.1：封禁账号不允许换发新令牌，否则封禁只是「访问令牌到期前的临时效果」
        if (UserStatus.isBanned(existedUser.getStatus())) {
            log.warn("刷新令牌换发被拒绝, 账号已被封禁, userId={}", userId);
            throw new BusinessException(ErrorCode.USER_BANNED);
        }
        // 步骤6：签发新一套令牌并登记新刷新令牌，旧刷新令牌此时已失效（轮换完成）
        LoginVO loginResult = buildLoginVO(existedUser);
        log.info("刷新令牌换发成功, userId={}, 已轮换旧tokenId={}", userId, tokenId);
        return loginResult;
    }

    /**
     * 登出：吊销当前访问令牌；若提交了刷新令牌，一并吊销。
     *
     * <p>重复登出不报错：吊销标记是覆盖写、刷新令牌登记是删除，两个操作本身幂等。</p>
     *
     * @param userId       当前登录用户ID
     * @param tokenId      当前访问令牌的 jti
     * @param refreshToken 刷新令牌，选填，可为 null
     */
    @Override
    public void logout(Long userId, String tokenId, String refreshToken) {
        // 步骤1：身份兜底校验，没有 jti 说明不是访问令牌认证（例如 API Key），无法吊销
        if (userId == null || !StringUtils.hasText(tokenId)) {
            log.warn("登出被拒绝, 缺少用户身份或令牌标识, userId={}, tokenId={}", userId, tokenId);
            throw new BusinessException(ErrorCode.COMMON_UNAUTHORIZED);
        }
        log.info("登出开始, userId={}, tokenId={}", userId, tokenId);
        // 步骤2：吊销当前访问令牌，在剩余有效期内立即失效
        tokenStore.revokeAccessToken(tokenId);
        // 步骤3：提交了刷新令牌则一并作废；解析失败只告警不阻断，登出必须尽量成功
        if (StringUtils.hasText(refreshToken)) {
            try {
                Claims refreshClaims = jwtTokenProvider.parseRefreshToken(refreshToken.trim());
                if (StringUtils.hasText(refreshClaims.getId())) {
                    tokenStore.revokeRefreshToken(refreshClaims.getId());
                }
            } catch (JwtException | IllegalArgumentException jwtException) {
                log.warn("登出时刷新令牌无效, 跳过吊销, userId={}, reason={}", userId, jwtException.getMessage());
            }
        }
        log.info("登出完成, userId={}, tokenId={}", userId, tokenId);
    }

    /**
     * 查询当前登录用户信息。
     *
     * @param userId 当前登录用户ID
     * @return 用户对外信息
     */
    @Override
    public UserVO queryCurrentUserInfo(Long userId) {
        // 步骤1：入参兜底校验，用户ID来自认证上下文，正常不会为空
        if (userId == null) {
            log.warn("查询当前用户信息被拒绝, userId 为空");
            throw new BusinessException(ErrorCode.COMMON_UNAUTHORIZED);
        }
        // 步骤2：查库，账号已注销时按未登录处理
        User existedUser = userMapper.selectById(userId);
        if (existedUser == null) {
            log.warn("查询当前用户信息被拒绝, 账号不存在或已注销, userId={}", userId);
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        // 步骤3：转换为对外 VO，天然不含密码哈希与 api_key
        UserVO currentUserInfo = UserVO.from(existedUser);
        log.info("查询当前用户信息成功, userId={}", userId);
        return currentUserInfo;
    }

    /**
     * 重新签发 Agent API Key，旧密钥立即失效。
     *
     * @param userId 当前登录用户ID
     * @return 新密钥明文与使用方式
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApiKeyVO rotateApiKey(Long userId) {
        // 步骤1：身份兜底校验，用户ID来自认证上下文，正常不会为空
        if (userId == null) {
            log.warn("签发 Agent 密钥被拒绝, userId 为空");
            throw new BusinessException(ErrorCode.COMMON_UNAUTHORIZED);
        }
        log.info("签发 Agent 密钥开始, userId={}", userId);
        // 步骤2：生成明文密钥与摘要，明文只在返回值中出现一次
        String rawApiKey = ApiKeyCodec.generate();
        String apiKeyDigest = ApiKeyCodec.digest(rawApiKey);
        // 步骤3：只更新 api_key 一列，避免整实体更新覆盖并发修改的其它字段；逻辑删除条件自动追加
        int updatedRows = userMapper.update(null, new LambdaUpdateWrapper<User>()
                .set(User::getApiKey, apiKeyDigest)
                .eq(User::getId, userId));
        if (updatedRows == 0) {
            log.warn("签发 Agent 密钥失败, 账号不存在或已注销, userId={}", userId);
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        // 步骤4：组装返回结构，日志只记录摘要前缀用于排查，密钥明文不落日志
        ApiKeyVO apiKeyInfo = new ApiKeyVO();
        apiKeyInfo.setApiKey(rawApiKey);
        apiKeyInfo.setGeneratedAt(LocalDateTime.now());
        apiKeyInfo.setUsage(API_KEY_USAGE_HINT);
        // 步骤5：日志只打印不可逆摘要的前缀，明文 rawApiKey 全程不出现在日志中
        String digestHead = apiKeyDigest.substring(0, DIGEST_LOG_PREFIX_LENGTH);
        log.info("签发 Agent 密钥完成, userId={}, digestHead={}", userId, digestHead);
        return apiKeyInfo;
    }

    /**
     * 签发访问令牌与刷新令牌并组装登录结果。
     *
     * @param user 已通过校验的用户实体
     * @return 访问令牌、刷新令牌、有效期与用户信息
     */
    private LoginVO buildLoginVO(User user) {
        // 步骤1：签发访问令牌（用于每次请求鉴权）
        IssuedToken accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getUsername());
        // 步骤2：签发刷新令牌，并把 jti 登记到 Redis——只有登记过的刷新令牌才能换发，伪造的换不出新令牌
        IssuedToken refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), user.getUsername());
        tokenStore.registerRefreshToken(refreshToken.tokenId(), user.getId(), refreshToken.expiresInMillis());
        // 步骤3：组装返回结构，令牌明文只在此处流转，绝不写日志
        LoginVO loginResult = new LoginVO();
        loginResult.setAccessToken(accessToken.tokenValue());
        loginResult.setExpiresIn(accessToken.expiresInMillis());
        loginResult.setRefreshToken(refreshToken.tokenValue());
        loginResult.setRefreshExpiresIn(refreshToken.expiresInMillis());
        loginResult.setUserInfo(UserVO.from(user));
        log.info("登录结果组装完成, userId={}, expiresIn={} ms, refreshExpiresIn={} ms",
                user.getId(), loginResult.getExpiresIn(), loginResult.getRefreshExpiresIn());
        return loginResult;
    }
}
