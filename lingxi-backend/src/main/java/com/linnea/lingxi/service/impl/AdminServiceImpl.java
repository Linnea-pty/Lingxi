package com.linnea.lingxi.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.linnea.lingxi.common.cursor.TimeIdCursor;
import com.linnea.lingxi.common.enums.UserRole;
import com.linnea.lingxi.common.enums.UserStatus;
import com.linnea.lingxi.common.exception.BusinessException;
import com.linnea.lingxi.common.exception.ErrorCode;
import com.linnea.lingxi.dto.AdminUserQueryDTO;
import com.linnea.lingxi.dto.BanUserDTO;
import com.linnea.lingxi.entity.User;
import com.linnea.lingxi.mapper.UserMapper;
import com.linnea.lingxi.service.AdminService;
import com.linnea.lingxi.service.CommentService;
import com.linnea.lingxi.service.PostService;
import com.linnea.lingxi.vo.AdminUserPageVO;
import com.linnea.lingxi.vo.AdminUserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理员后台业务实现。
 *
 * <p>三条治理原则：
 * ① 封禁不删数据：账号行保留，其历史动态与评论继续展示，只是不能再登录与写入；
 * ② 管理员之间不互相封禁：一旦允许，误操作就可能把后台全部锁死，降级要走数据库变更；
 * ③ 后台删内容复用作者自删的同一套删除范围（动态级联评论、评论级联回复），不另写一套「只删眼前这一条」的逻辑。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    /** 列表默认每页条数，与 ListQueryDTO 的默认值保持一致。 */
    private static final int DEFAULT_PAGE_SIZE = 10;

    /** 列表每页条数上限，与 ListQueryDTO 的 @Max(30) 保持一致。 */
    private static final int MAX_PAGE_SIZE = 30;

    /** 封禁原因列长度上限，与 V7 迁移中的 VARCHAR(200) 保持一致。 */
    private static final int BAN_REASON_MAX_LENGTH = 200;

    /** 用户数据访问。 */
    private final UserMapper userMapper;

    /** 动态业务服务：后台删除动态复用作者的删除范围。 */
    private final PostService postService;

    /** 评论业务服务：后台删除评论复用作者的级联范围。 */
    private final CommentService commentService;

    /**
     * 分页查询用户列表。
     *
     * @param queryInfo 查询参数（游标、条数、关键字前缀、状态筛选）
     * @return 用户列表分页结果
     */
    @Override
    public AdminUserPageVO queryUserPage(AdminUserQueryDTO queryInfo) {
        // 步骤1：归一化条数、解码游标、转义关键字里的 LIKE 通配符
        int pageSize = resolvePageSize(queryInfo);
        TimeIdCursor userCursor = TimeIdCursor.decode(queryInfo.getCursor());
        String escapedKeyword = escapeLikeKeyword(queryInfo.getKeyword());
        log.info("查询后台用户列表开始, pageSize={}, hasCursor={}, status={}, keywordLength={}",
                pageSize, userCursor != null, queryInfo.getStatus(),
                escapedKeyword == null ? 0 : escapedKeyword.length());
        // 步骤2：多取一条探测下一页，游标键与 SQL 的 ORDER BY 一致
        List<User> userRows = userMapper.selectAdminUserList(escapedKeyword, queryInfo.getStatus(),
                userCursor == null ? null : userCursor.getCreateTime(),
                userCursor == null ? null : userCursor.getId(),
                pageSize + 1);
        // 步骤3：裁剪成页并生成下一页游标
        boolean hasMore = userRows.size() > pageSize;
        List<User> pageUserRows = hasMore ? new ArrayList<>(userRows.subList(0, pageSize)) : userRows;
        String nextCursor = null;
        if (hasMore && !pageUserRows.isEmpty()) {
            User lastUser = pageUserRows.get(pageUserRows.size() - 1);
            nextCursor = TimeIdCursor.encode(lastUser.getCreateTime(), lastUser.getId());
        }
        // 步骤4：实体转出参，编码翻译成中文描述
        List<AdminUserVO> userList = new ArrayList<>(pageUserRows.size());
        for (User userRow : pageUserRows) {
            userList.add(AdminUserVO.from(userRow));
        }
        AdminUserPageVO userPage = new AdminUserPageVO();
        userPage.setUserList(userList);
        userPage.setNextCursor(nextCursor);
        userPage.setHasMore(hasMore);
        log.info("查询后台用户列表完成, itemCount={}, hasMore={}, status={}",
                userList.size(), hasMore, queryInfo.getStatus());
        return userPage;
    }

    /**
     * 封禁账号。
     *
     * @param userId         被封禁的用户ID
     * @param banInfo        封禁请求参数（原因可空）
     * @param operatorUserId 操作的管理员ID
     * @return 封禁后的账号信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminUserVO banUser(Long userId, BanUserDTO banInfo, Long operatorUserId) {
        // 步骤1：参数与自封兜底——封自己等于自锁后台，直接拒绝
        if (userId == null || userId <= 0) {
            log.warn("封禁账号被拒绝, 用户ID非法, userId={}, operatorUserId={}", userId, operatorUserId);
            throw new BusinessException(ErrorCode.COMMON_PARAM_ERROR, "用户ID不合法");
        }
        if (userId.equals(operatorUserId)) {
            log.warn("封禁账号被拒绝, 不能封禁自己, userId={}", userId);
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN, "不能封禁自己");
        }
        // 步骤2：目标必须存在（已注销的账号查不到）
        User targetUser = userMapper.selectById(userId);
        if (targetUser == null) {
            log.warn("封禁账号被拒绝, 用户不存在或已注销, userId={}, operatorUserId={}", userId, operatorUserId);
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        // 步骤3：管理员之间不互相封禁，避免把后台锁死
        if (UserRole.ADMIN == UserRole.fromCode(targetUser.getRole())) {
            log.warn("封禁账号被拒绝, 目标为管理员, userId={}, operatorUserId={}", userId, operatorUserId);
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN, "不能封禁管理员");
        }
        // 步骤4：归一化原因（裁空白 + 兜底长度），封禁不强制填原因
        String banReason = normalizeBanReason(banInfo);
        // 步骤5：更新状态。已封禁账号重复封禁只会刷新原因，因此不校验影响行数（值未变时 MySQL 返回 0）
        User updateUser = new User();
        updateUser.setStatus(UserStatus.BANNED.getCode());
        updateUser.setBanReason(banReason);
        int updatedRows = userMapper.update(updateUser,
                new LambdaUpdateWrapper<User>().eq(User::getId, userId));
        // 步骤6：回读返回最新状态，前端直接覆盖行数据
        User bannedUser = userMapper.selectById(userId);
        log.info("封禁账号完成, userId={}, operatorUserId={}, updatedRows={}, banReasonLength={}",
                userId, operatorUserId, updatedRows, banReason.length());
        return AdminUserVO.from(bannedUser);
    }

    /**
     * 解封账号。
     *
     * @param userId         被解封的用户ID
     * @param operatorUserId 操作的管理员ID
     * @return 解封后的账号信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminUserVO unbanUser(Long userId, Long operatorUserId) {
        // 步骤1：参数兜底
        if (userId == null || userId <= 0) {
            log.warn("解封账号被拒绝, 用户ID非法, userId={}, operatorUserId={}", userId, operatorUserId);
            throw new BusinessException(ErrorCode.COMMON_PARAM_ERROR, "用户ID不合法");
        }
        // 步骤2：目标必须存在
        User targetUser = userMapper.selectById(userId);
        if (targetUser == null) {
            log.warn("解封账号被拒绝, 用户不存在或已注销, userId={}, operatorUserId={}", userId, operatorUserId);
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        // 步骤3：恢复状态并清空原因，避免解封后列表还显示上一次的封禁理由
        User updateUser = new User();
        updateUser.setStatus(UserStatus.NORMAL.getCode());
        updateUser.setBanReason("");
        int updatedRows = userMapper.update(updateUser,
                new LambdaUpdateWrapper<User>().eq(User::getId, userId));
        // 步骤4：回读返回最新状态
        User unbannedUser = userMapper.selectById(userId);
        log.info("解封账号完成, userId={}, operatorUserId={}, updatedRows={}",
                userId, operatorUserId, updatedRows);
        return AdminUserVO.from(unbannedUser);
    }

    /**
     * 删除任意动态。
     *
     * @param postId         动态ID
     * @param operatorUserId 操作的管理员ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removePost(Long postId, Long operatorUserId) {
        // 步骤1：记录后台治理动作，便于与作者自删在日志里区分
        log.info("后台删除动态开始, postId={}, operatorUserId={}", postId, operatorUserId);
        // 步骤2：复用作者自删的删除范围（动态 + 其下全部评论），只跳过归属校验
        postService.removePostByAdmin(postId, operatorUserId);
        log.info("后台删除动态完成, postId={}, operatorUserId={}", postId, operatorUserId);
    }

    /**
     * 删除任意评论。
     *
     * @param commentId      评论ID
     * @param operatorUserId 操作的管理员ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeComment(Long commentId, Long operatorUserId) {
        // 步骤1：记录后台治理动作
        log.info("后台删除评论开始, commentId={}, operatorUserId={}", commentId, operatorUserId);
        // 步骤2：复用作者自删的级联范围（评论 + 其下全部回复），只跳过归属校验
        commentService.removeCommentByAdmin(commentId, operatorUserId);
        log.info("后台删除评论完成, commentId={}, operatorUserId={}", commentId, operatorUserId);
    }

    /**
     * 归一化封禁原因：裁掉首尾空白、超长截断、null 转空串。
     *
     * @param banInfo 封禁请求参数，可为 null
     * @return 可直接落库的封禁原因
     */
    private String normalizeBanReason(BanUserDTO banInfo) {
        // 步骤1：未传原因时落空串，列定义 NOT NULL DEFAULT ''
        if (banInfo == null || banInfo.getReason() == null) {
            log.info("封禁原因未填写, 按空串落库");
            return "";
        }
        // 步骤2：裁空白后按列长度截断（@Size 已拦超长，这里防内部调用绕过参数校验）
        String trimmedReason = banInfo.getReason().trim();
        String banReason = trimmedReason.length() <= BAN_REASON_MAX_LENGTH
                ? trimmedReason
                : trimmedReason.substring(0, BAN_REASON_MAX_LENGTH);
        log.info("封禁原因归一化完成, originalLength={}, finalLength={}",
                trimmedReason.length(), banReason.length());
        return banReason;
    }

    /**
     * 转义关键字中的 LIKE 通配符。
     *
     * <p>WHY 必须转义：关键字直接拼进 {@code LIKE CONCAT(#{keyword}, '%')} 时，
     * 用户输入 {@code %} 会让前缀匹配悄悄变成全模糊匹配（索引失效），输入 {@code _} 会意外匹配任意单字。
     * MySQL 默认转义符是反斜杠，因此把反斜杠、百分号、下划线各自加一层反斜杠即可。</p>
     *
     * @param keyword 原始关键字，可为 null
     * @return 转义后的关键字；空白关键字返回 null 表示不筛选
     */
    private String escapeLikeKeyword(String keyword) {
        // 步骤1：空白关键字视为不筛选
        if (keyword == null || keyword.trim().isEmpty()) {
            log.info("后台用户列表关键字为空, 不启用关键字筛选");
            return null;
        }
        // 步骤2：先转义反斜杠本身，再转义百分号与下划线（顺序不能反，否则会重复转义）
        String escapedKeyword = keyword.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        log.info("后台用户列表关键字转义完成, originalLength={}, escapedLength={}",
                keyword.trim().length(), escapedKeyword.length());
        return escapedKeyword;
    }

    /**
     * 解析每页条数：未传走默认，越界按上下限兜底。
     *
     * @param queryInfo 查询参数
     * @return 实际使用的每页条数
     */
    private int resolvePageSize(AdminUserQueryDTO queryInfo) {
        // 步骤1：未传走默认，传了则上下界兜底（Bean Validation 正常已拦住越界请求）
        Integer requestSize = queryInfo.getSize();
        int pageSize = requestSize == null
                ? DEFAULT_PAGE_SIZE
                : Math.max(1, Math.min(requestSize, MAX_PAGE_SIZE));
        // 步骤2：记录最终条数，便于排查「参数已传但按默认值分页」
        log.info("后台用户列表分页条数解析完成, pageSize={}", pageSize);
        return pageSize;
    }
}
