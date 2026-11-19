package com.linnea.lingxi.service;

import com.linnea.lingxi.dto.AdminUserQueryDTO;
import com.linnea.lingxi.dto.BanUserDTO;
import com.linnea.lingxi.vo.AdminUserPageVO;
import com.linnea.lingxi.vo.AdminUserVO;

/**
 * 管理员后台业务接口：账号治理与内容治理。
 *
 * <p>权限边界：本接口的所有实现都假定「调用者已是管理员」——
 * 判定放在控制器上的 {@code @PreAuthorize("hasRole('ADMIN')")}，业务层不再重复判角色。
 * 业务层只负责「管理员也不能做的操作」（封自己、封另一个管理员），这类规则与角色无关，属于业务不变量。</p>
 */
public interface AdminService {

    /**
     * 分页查询用户列表。
     *
     * @param queryInfo 查询参数（游标、条数、关键字前缀、状态筛选）
     * @return 用户列表分页结果
     */
    AdminUserPageVO queryUserPage(AdminUserQueryDTO queryInfo);

    /**
     * 封禁账号：该账号的旧访问令牌、刷新令牌与 Agent 密钥在下一次请求校验时立即失效。
     *
     * <p>幂等：对已封禁账号再次封禁只刷新封禁原因，不报错。</p>
     *
     * @param userId         被封禁的用户ID
     * @param banInfo        封禁请求参数（原因可空）
     * @param operatorUserId 操作的管理员ID
     * @return 封禁后的账号信息
     */
    AdminUserVO banUser(Long userId, BanUserDTO banInfo, Long operatorUserId);

    /**
     * 解封账号：解除后该账号可重新登录，旧令牌仍需重新登录或刷新才会生效。
     *
     * <p>幂等：对未封禁账号调用同样返回成功。</p>
     *
     * @param userId         被解封的用户ID
     * @param operatorUserId 操作的管理员ID
     * @return 解封后的账号信息
     */
    AdminUserVO unbanUser(Long userId, Long operatorUserId);

    /**
     * 删除任意动态（逻辑删除），级联删除其下全部评论。
     *
     * @param postId         动态ID
     * @param operatorUserId 操作的管理员ID
     */
    void removePost(Long postId, Long operatorUserId);

    /**
     * 删除任意评论（逻辑删除），连同其下全部回复一起删除。
     *
     * @param commentId      评论ID
     * @param operatorUserId 操作的管理员ID
     */
    void removeComment(Long commentId, Long operatorUserId);
}
