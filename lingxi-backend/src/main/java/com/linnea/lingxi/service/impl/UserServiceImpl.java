package com.linnea.lingxi.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.linnea.lingxi.common.cursor.TimeIdCursor;
import com.linnea.lingxi.common.exception.BusinessException;
import com.linnea.lingxi.common.exception.ErrorCode;
import com.linnea.lingxi.common.file.AvatarStorage;
import com.linnea.lingxi.dto.ListQueryDTO;
import com.linnea.lingxi.dto.UpdateProfileDTO;
import com.linnea.lingxi.entity.User;
import com.linnea.lingxi.mapper.UserFollowMapper;
import com.linnea.lingxi.mapper.UserMapper;
import com.linnea.lingxi.service.NotificationService;
import com.linnea.lingxi.service.UserService;
import com.linnea.lingxi.vo.FollowResultVO;
import com.linnea.lingxi.vo.FollowUserPageVO;
import com.linnea.lingxi.vo.FollowUserVO;
import com.linnea.lingxi.vo.UserProfileVO;
import com.linnea.lingxi.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 用户资料与关注关系业务实现。
 *
 * <p>三条一致性约定：
 * ① 关注/取关都靠唯一键 + upsert/条件更新表达，重复调用不报错，前端可无脑重试；
 * ② 列表里的 isFollowing 由一次批量 IN 查询回填，不逐条查库（避免 N+1）；
 * ③ 头像先落盘再写库，写库失败回删文件，写库成功才删旧文件，保证不留「库里指向不存在的文件」。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    /** 列表默认每页条数。 */
    private static final int DEFAULT_LIST_PAGE_SIZE = 10;

    /** 列表每页条数上限：与 ListQueryDTO 的 @Max(30) 保持一致。 */
    private static final int MAX_LIST_PAGE_SIZE = 30;

    /** 昵称最小长度（trim 后）。 */
    private static final int MIN_NICKNAME_LENGTH = 2;

    /** 昵称最大长度（trim 后）。 */
    private static final int MAX_NICKNAME_LENGTH = 20;

    /** 用户数据访问。 */
    private final UserMapper userMapper;

    /** 关注关系数据访问。 */
    private final UserFollowMapper userFollowMapper;

    /** 头像本地存储组件。 */
    private final AvatarStorage avatarStorage;

    /** 通知业务服务：首次建立关注关系时投递「关注了你」。 */
    private final NotificationService notificationService;

    /**
     * 查询用户主页资料。
     *
     * @param targetUserId  被查看的用户ID
     * @param currentUserId 当前登录用户ID
     * @return 用户主页资料
     */
    @Override
    public UserProfileVO queryUserProfile(Long targetUserId, Long currentUserId) {
        // 步骤1：目标用户必须存在，已注销按不存在处理
        User targetUser = requireExistedUser(targetUserId);
        // 步骤2：粉丝数与关注数各一次 COUNT，均命中 (等值列, 状态) 索引前缀
        long followerCount = userFollowMapper.countFollower(targetUserId);
        long followingCount = userFollowMapper.countFollowing(targetUserId);
        // 步骤3：判断我是否已关注他；看自己的主页时恒为 false（不能关注自己）
        boolean isFollowing = false;
        if (currentUserId != null && !currentUserId.equals(targetUserId)) {
            isFollowing = !userFollowMapper.selectFollowingIdList(currentUserId, List.of(targetUserId)).isEmpty();
        }
        // 步骤4：组装对外对象
        UserProfileVO userProfile = new UserProfileVO();
        userProfile.setUserId(targetUser.getId());
        userProfile.setUsername(targetUser.getUsername());
        userProfile.setNickname(targetUser.getNickname());
        userProfile.setAvatarUrl(targetUser.getAvatarUrl() == null ? "" : targetUser.getAvatarUrl());
        userProfile.setFollowerCount(followerCount);
        userProfile.setFollowingCount(followingCount);
        userProfile.setIsFollowing(isFollowing);
        log.info("查询用户主页资料成功, targetUserId={}, currentUserId={}, followerCount={}, followingCount={}, isFollowing={}",
                targetUserId, currentUserId, followerCount, followingCount, isFollowing);
        return userProfile;
    }

    /**
     * 修改当前用户的昵称。
     *
     * @param userId     当前登录用户ID
     * @param updateInfo 修改请求参数
     * @return 修改后的用户信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO updateProfile(Long userId, UpdateProfileDTO updateInfo) {
        // 步骤1：先确认账号存在，已注销账号不允许改资料
        requireExistedUser(userId);
        // 步骤2：裁掉首尾空白后再校验长度——Bean Validation 只看到原串，" a " 会通过 @Size 却存入单字符
        String normalizedNickname = updateInfo.getNickname() == null ? "" : updateInfo.getNickname().trim();
        if (normalizedNickname.length() < MIN_NICKNAME_LENGTH || normalizedNickname.length() > MAX_NICKNAME_LENGTH) {
            log.warn("修改昵称被拒绝, 裁掉空白后长度不合法, userId={}, length={}",
                    userId, normalizedNickname.length());
            throw new BusinessException(ErrorCode.COMMON_PARAM_ERROR,
                    "昵称长度需在 " + MIN_NICKNAME_LENGTH + "~" + MAX_NICKNAME_LENGTH + " 个字符之间");
        }
        // 步骤3：只更新 nickname 一列，WHERE 带主键防止越权改到别人
        int updatedRows = userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getNickname, normalizedNickname));
        // 步骤4：回读落库结果，保证响应里的就是库里的值
        User reloadedUser = requireExistedUser(userId);
        log.info("修改昵称完成, userId={}, updatedRows={}, nickname={}", userId, updatedRows, normalizedNickname);
        return UserVO.from(reloadedUser);
    }

    /**
     * 上传并更新当前用户的头像。
     *
     * @param userId     当前登录用户ID
     * @param avatarFile 头像文件
     * @return 修改后的用户信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO updateAvatar(Long userId, MultipartFile avatarFile) {
        // 步骤1：先确认账号存在，并记住旧头像地址用于事后清理
        User existedUser = requireExistedUser(userId);
        String oldAvatarUrl = existedUser.getAvatarUrl();
        // 步骤2：校验并落盘，返回新的相对 URL（失败时抛业务异常，尚未写库）
        String newAvatarUrl = avatarStorage.store(userId, avatarFile);
        // 步骤3：写库；失败要回删刚落的文件，否则磁盘会堆积没有任何引用指向的垃圾文件
        try {
            userMapper.update(null, new LambdaUpdateWrapper<User>()
                    .eq(User::getId, userId)
                    .set(User::getAvatarUrl, newAvatarUrl));
        } catch (RuntimeException updateException) {
            log.error("头像地址写库失败, 回删新文件, userId={}, newAvatarUrl={}", userId, newAvatarUrl, updateException);
            avatarStorage.deleteByUrl(newAvatarUrl);
            throw updateException;
        }
        // 步骤4：写库成功后清理旧头像，失败只记日志不影响本次请求
        avatarStorage.deleteByUrl(oldAvatarUrl);
        // 步骤5：回读并返回最新资料
        User reloadedUser = requireExistedUser(userId);
        log.info("更新头像完成, userId={}, newAvatarUrl={}, oldAvatarUrl={}", userId, newAvatarUrl, oldAvatarUrl);
        return UserVO.from(reloadedUser);
    }

    /**
     * 关注某用户。
     *
     * @param targetUserId  被关注用户ID
     * @param currentUserId 当前登录用户ID
     * @return 关注结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FollowResultVO followUser(Long targetUserId, Long currentUserId) {
        // 步骤1：先排除自关注与非法参数
        validateFollowTarget(targetUserId, currentUserId);
        // 步骤2：目标用户必须存在（已注销不可关注）
        requireExistedUser(targetUserId);
        // 步骤3：先查是否已关注，用于决定要不要发通知。
        // WHY 不用 upsert 的影响行数判断「是否首次」：MySQL 驱动默认按 found-rows 语义返回，
        // 「更新已有行」也返回 1，与「新插入」无法区分（真实测试中重复关注因此产生了第二条通知）。
        boolean alreadyFollowed = !userFollowMapper
                .selectFollowingIdList(currentUserId, List.of(targetUserId)).isEmpty();
        // 步骤4：upsert 建立关系，重复关注只刷新关注时间
        int affectedRows = userFollowMapper.upsertFollow(currentUserId, targetUserId);
        // 步骤4.1：只有「本次之前未关注」才通知对方。
        // 并发首次关注（两个请求同时查到未关注）可能产生两条通知，属于可接受的冗余：通知是追加型数据，
        // 重复一条的代价远低于「用悲观锁串行化关注」带来的写放大。
        if (!alreadyFollowed) {
            notificationService.notifyFollowed(targetUserId, currentUserId);
        }
        // 步骤5：返回操作后的状态与最新粉丝数
        long followerCount = userFollowMapper.countFollower(targetUserId);
        log.info("关注用户完成, currentUserId={}, targetUserId={}, alreadyFollowed={}, affectedRows={}, followerCount={}",
                currentUserId, targetUserId, alreadyFollowed, affectedRows, followerCount);
        return buildFollowResult(Boolean.TRUE, followerCount);
    }

    /**
     * 取关某用户。
     *
     * @param targetUserId  被取关用户ID
     * @param currentUserId 当前登录用户ID
     * @return 取关结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FollowResultVO unfollowUser(Long targetUserId, Long currentUserId) {
        // 步骤1：先排除自关注与非法参数
        validateFollowTarget(targetUserId, currentUserId);
        // 步骤2：目标用户必须存在，避免对不存在的ID返回「取关成功」造成前端状态错乱
        requireExistedUser(targetUserId);
        // 步骤3：条件更新置为未关注，原本就未关注时影响 0 行，按幂等成功处理
        int affectedRows = userFollowMapper.cancelFollow(currentUserId, targetUserId);
        // 步骤4：返回操作后的状态与最新粉丝数
        long followerCount = userFollowMapper.countFollower(targetUserId);
        log.info("取关用户完成, currentUserId={}, targetUserId={}, affectedRows={}, followerCount={}",
                currentUserId, targetUserId, affectedRows, followerCount);
        return buildFollowResult(Boolean.FALSE, followerCount);
    }

    /**
     * 查询某用户关注的人。
     *
     * @param targetUserId  列表归属用户ID
     * @param currentUserId 当前登录用户ID
     * @param listQueryInfo 分页参数
     * @return 关注列表分页结果
     */
    @Override
    public FollowUserPageVO queryFollowingPage(Long targetUserId, Long currentUserId, ListQueryDTO listQueryInfo) {
        // 步骤1：列表归属用户必须存在
        requireExistedUser(targetUserId);
        // 步骤2：解析条数与游标
        int pageSize = resolveListPageSize(listQueryInfo);
        TimeIdCursor cursor = TimeIdCursor.decode(listQueryInfo.getCursor());
        // 步骤3：多查一条用于探测 hasMore，避免额外 COUNT
        List<FollowUserVO> queriedList = userFollowMapper.selectFollowingList(targetUserId,
                cursor == null ? null : cursor.getCreateTime(),
                cursor == null ? null : cursor.getId(),
                pageSize + 1);
        // 步骤4：裁剪、生成游标、回填 isFollowing
        FollowUserPageVO followingPage = buildFollowUserPage(queriedList, pageSize, currentUserId);
        log.info("查询关注列表完成, targetUserId={}, currentUserId={}, itemCount={}, hasMore={}",
                targetUserId, currentUserId, followingPage.getUserList().size(), followingPage.getHasMore());
        return followingPage;
    }

    /**
     * 查询某用户的粉丝。
     *
     * @param targetUserId  列表归属用户ID
     * @param currentUserId 当前登录用户ID
     * @param listQueryInfo 分页参数
     * @return 粉丝列表分页结果
     */
    @Override
    public FollowUserPageVO queryFollowersPage(Long targetUserId, Long currentUserId, ListQueryDTO listQueryInfo) {
        // 步骤1：列表归属用户必须存在
        requireExistedUser(targetUserId);
        // 步骤2：解析条数与游标
        int pageSize = resolveListPageSize(listQueryInfo);
        TimeIdCursor cursor = TimeIdCursor.decode(listQueryInfo.getCursor());
        // 步骤3：多查一条用于探测 hasMore
        List<FollowUserVO> queriedList = userFollowMapper.selectFollowerList(targetUserId,
                cursor == null ? null : cursor.getCreateTime(),
                cursor == null ? null : cursor.getId(),
                pageSize + 1);
        // 步骤4：裁剪、生成游标、回填 isFollowing
        FollowUserPageVO followerPage = buildFollowUserPage(queriedList, pageSize, currentUserId);
        log.info("查询粉丝列表完成, targetUserId={}, currentUserId={}, itemCount={}, hasMore={}",
                targetUserId, currentUserId, followerPage.getUserList().size(), followerPage.getHasMore());
        return followerPage;
    }

    /**
     * 校验关注动作的入参与自关注。
     *
     * @param targetUserId  被关注用户ID
     * @param currentUserId 当前登录用户ID
     */
    private void validateFollowTarget(Long targetUserId, Long currentUserId) {
        // 步骤1：登录态缺失按未认证处理，目标缺失按参数错误处理
        if (currentUserId == null) {
            log.warn("关注动作被拒绝, 缺少当前登录用户");
            throw new BusinessException(ErrorCode.COMMON_UNAUTHORIZED);
        }
        if (targetUserId == null) {
            log.warn("关注动作被拒绝, 目标用户ID为空, currentUserId={}", currentUserId);
            throw new BusinessException(ErrorCode.COMMON_PARAM_ERROR, "目标用户ID不能为空");
        }
        // 步骤2：自关注会让计数失真且无业务含义，直接拒绝
        if (targetUserId.equals(currentUserId)) {
            log.warn("关注动作被拒绝, 不能关注自己, userId={}", currentUserId);
            throw new BusinessException(ErrorCode.USER_FOLLOW_SELF);
        }
    }

    /**
     * 把查询结果裁剪成页并回填 isFollowing。
     *
     * @param queriedList   已多查一条的原始结果
     * @param pageSize      每页条数
     * @param currentUserId 当前登录用户ID，为空时不回填（全部 false）
     * @return 分页结果
     */
    private FollowUserPageVO buildFollowUserPage(List<FollowUserVO> queriedList, int pageSize, Long currentUserId) {
        // 步骤1：多查一条则说明还有下一页，裁掉多余那条
        boolean hasMore = queriedList.size() > pageSize;
        List<FollowUserVO> pageList = hasMore ? new ArrayList<>(queriedList.subList(0, pageSize)) : queriedList;
        // 步骤2：用本页最后一条的 (关注时间, 关系ID) 生成游标，与 ORDER BY 同一组列
        String nextCursor = null;
        if (hasMore && !pageList.isEmpty()) {
            FollowUserVO lastItem = pageList.get(pageList.size() - 1);
            nextCursor = TimeIdCursor.encode(lastItem.getFollowedAt(), lastItem.getFollowId());
        }
        // 步骤3：一次 IN 查询回填「我是否已关注本页这些人」，避免逐条查库
        Set<Long> followedUserIdSet = new HashSet<>();
        if (currentUserId != null && !pageList.isEmpty()) {
            List<Long> targetUserIdList = pageList.stream().map(FollowUserVO::getUserId).toList();
            followedUserIdSet.addAll(userFollowMapper.selectFollowingIdList(currentUserId, targetUserIdList));
        }
        pageList.forEach(followUserInfo ->
                followUserInfo.setIsFollowing(followedUserIdSet.contains(followUserInfo.getUserId())));
        // 步骤4：组装分页对象
        FollowUserPageVO followUserPage = new FollowUserPageVO();
        followUserPage.setUserList(pageList);
        followUserPage.setNextCursor(nextCursor);
        followUserPage.setHasMore(hasMore);
        log.info("关注类列表分页组装完成, pageSize={}, itemCount={}, hasMore={}, followedCount={}",
                pageSize, pageList.size(), hasMore, followedUserIdSet.size());
        return followUserPage;
    }

    /**
     * 组装关注/取关结果。
     *
     * @param following     操作后的关注状态
     * @param followerCount 被关注方最新粉丝数
     * @return 关注结果
     */
    private FollowResultVO buildFollowResult(Boolean following, long followerCount) {
        // 步骤1：写入状态与计数
        FollowResultVO followResult = new FollowResultVO();
        followResult.setFollowing(following);
        followResult.setFollowerCount(followerCount);
        // 步骤2：记录组装结果，便于把「操作状态」与「最新粉丝数」串进同一条日志
        log.info("关注结果组装完成, following={}, followerCount={}", following, followerCount);
        return followResult;
    }

    /**
     * 查询用户并断言存在。
     *
     * @param userId 用户ID
     * @return 用户实体
     */
    private User requireExistedUser(Long userId) {
        // 步骤1：ID 为空按参数错误处理（路径参数缺失或为 0）
        if (userId == null || userId <= 0) {
            log.warn("查询用户被拒绝, 用户ID非法, userId={}", userId);
            throw new BusinessException(ErrorCode.COMMON_PARAM_ERROR, "用户ID不合法");
        }
        // 步骤2：查库，逻辑删除的账号查不到，与「不存在」同口径
        User existedUser = userMapper.selectById(userId);
        if (existedUser == null) {
            log.warn("查询用户被拒绝, 账号不存在或已注销, userId={}", userId);
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return existedUser;
    }

    /**
     * 解析列表每页条数：未传走默认，越界按上下限兜底。
     *
     * @param listQueryInfo 分页参数
     * @return 实际使用的每页条数
     */
    private int resolveListPageSize(ListQueryDTO listQueryInfo) {
        // 步骤1：未传走默认，传了则上下界兜底（Bean Validation 正常已拦住越界请求）
        Integer requestSize = listQueryInfo.getSize();
        int pageSize = requestSize == null
                ? DEFAULT_LIST_PAGE_SIZE
                : Math.max(1, Math.min(requestSize, MAX_LIST_PAGE_SIZE));
        // 步骤2：记录最终条数，便于排查「参数已传但按默认值分页」的问题
        log.info("列表分页条数解析完成, pageSize={}", pageSize);
        return pageSize;
    }
}
