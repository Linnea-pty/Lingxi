package com.linnea.lingxi.service;

import com.linnea.lingxi.dto.ListQueryDTO;
import com.linnea.lingxi.dto.UpdateProfileDTO;
import com.linnea.lingxi.vo.FollowResultVO;
import com.linnea.lingxi.vo.FollowUserPageVO;
import com.linnea.lingxi.vo.UserProfileVO;
import com.linnea.lingxi.vo.UserVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户资料与关注关系业务服务。
 *
 * <p>职责边界：只处理「我改我的资料」与「我关注别人」两类动作，
 * 登录、令牌、密钥仍归 {@link AuthService}；发文、评论、点赞归各自服务。</p>
 */
public interface UserService {

    /**
     * 查询用户主页资料（含关注/粉丝计数与我的关注状态）。
     *
     * @param targetUserId  被查看的用户ID
     * @param currentUserId 当前登录用户ID，用于计算 isFollowing
     * @return 用户主页资料
     */
    UserProfileVO queryUserProfile(Long targetUserId, Long currentUserId);

    /**
     * 修改当前用户的昵称。
     *
     * @param userId     当前登录用户ID
     * @param updateInfo 修改请求参数
     * @return 修改后的用户信息
     */
    UserVO updateProfile(Long userId, UpdateProfileDTO updateInfo);

    /**
     * 上传并更新当前用户的头像。
     *
     * @param userId     当前登录用户ID
     * @param avatarFile 头像文件
     * @return 修改后的用户信息（avatarUrl 为新地址）
     */
    UserVO updateAvatar(Long userId, MultipartFile avatarFile);

    /**
     * 关注某用户：重复关注幂等，返回操作后的状态与最新粉丝数。
     *
     * @param targetUserId  被关注用户ID
     * @param currentUserId 当前登录用户ID
     * @return 关注结果
     */
    FollowResultVO followUser(Long targetUserId, Long currentUserId);

    /**
     * 取关某用户：重复取关幂等，返回操作后的状态与最新粉丝数。
     *
     * @param targetUserId  被取关用户ID
     * @param currentUserId 当前登录用户ID
     * @return 取关结果
     */
    FollowResultVO unfollowUser(Long targetUserId, Long currentUserId);

    /**
     * 查询某用户关注的人（公开信息，登录即可看）。
     *
     * @param targetUserId  列表归属用户ID
     * @param currentUserId 当前登录用户ID，用于回填 isFollowing
     * @param listQueryInfo 分页参数
     * @return 关注列表分页结果
     */
    FollowUserPageVO queryFollowingPage(Long targetUserId, Long currentUserId, ListQueryDTO listQueryInfo);

    /**
     * 查询某用户的粉丝（公开信息，登录即可看）。
     *
     * @param targetUserId  列表归属用户ID
     * @param currentUserId 当前登录用户ID，用于回填 isFollowing
     * @param listQueryInfo 分页参数
     * @return 粉丝列表分页结果
     */
    FollowUserPageVO queryFollowersPage(Long targetUserId, Long currentUserId, ListQueryDTO listQueryInfo);
}
