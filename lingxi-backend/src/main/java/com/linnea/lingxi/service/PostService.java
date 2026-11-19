package com.linnea.lingxi.service;

import com.linnea.lingxi.dto.CreatePostDTO;
import com.linnea.lingxi.dto.FeedQueryDTO;
import com.linnea.lingxi.dto.ListQueryDTO;
import com.linnea.lingxi.dto.UpdatePostDTO;
import com.linnea.lingxi.vo.FeedPageVO;
import com.linnea.lingxi.vo.PostPageVO;
import com.linnea.lingxi.vo.PostVO;

/**
 * 动态业务服务：发布动态与信息流浏览。
 *
 * <p>发布者身份一律由调用方从认证上下文传入，服务层不读请求参数中的用户ID，
 * 从结构上避免越权代发。</p>
 */
public interface PostService {

    /**
     * 发布一条动态。
     *
     * <p>来源与置顶由调用方身份决定：网页端发布记为 web 且不置顶；Agent 通过 API Key 代发记为 agent 且置顶。
     * 请求参数带 requestId 时启用幂等去重，相同幂等键的重复提交返回首次结果而非新建一条。</p>
     *
     * @param createPostInfo  发布请求参数，已通过 Bean Validation 校验
     * @param publisherUserId 当前登录用户ID，来自认证上下文
     * @param agent           是否为 Agent 身份，决定来源与置顶标记
     * @return 发布后的动态信息（含作者资料）
     */
    PostVO createPost(CreatePostDTO createPostInfo, Long publisherUserId, boolean agent);

    /**
     * 按键集分页查询全局信息流。
     *
     * @param feedQueryInfo 分页参数（游标 + 每页条数），已通过 Bean Validation 校验
     * @return 当前页动态及下一页游标
     */
    FeedPageVO queryFeed(FeedQueryDTO feedQueryInfo);

    /**
     * 修改动态正文。
     *
     * <p>仅作者可改：来源与置顶不允许通过修改接口变更，否则普通用户能把动态改成「Agent 置顶」。
     * 正文按与发布相同的规则清洗（trim，纯空白拒绝）。</p>
     *
     * @param postId         动态ID，来自路径参数
     * @param updatePostInfo 修改请求参数
     * @param operatorUserId 当前登录用户ID，来自认证上下文
     * @return 修改后的动态信息（含最新互动计数）
     */
    PostVO updatePost(Long postId, UpdatePostDTO updatePostInfo, Long operatorUserId);

    /**
     * 删除动态（逻辑删除），级联删除其下全部评论。
     *
     * <p>仅作者可删。点赞行不物理删除：取消/删除都靠 status 与 deleted_id 表达，
     * 保留行为审计；列表查询通过 JOIN post 过滤掉已删动态。</p>
     *
     * @param postId         动态ID
     * @param operatorUserId 当前登录用户ID
     */
    void deletePost(Long postId, Long operatorUserId);

    /**
     * 管理员删除任意动态（逻辑删除），级联删除其下全部评论。
     *
     * <p>与 {@link #deletePost(Long, Long)} 的唯一区别是不校验作者归属：
     * 「是不是管理员」由控制器上的 {@code hasRole('ADMIN')} 判定，业务层不再重复判权限，
     * 否则同一个删除动作在两处各写一份权限逻辑，迟早会漂移。</p>
     *
     * <p>删除范围与作者自删完全一致（动态 + 其下全部评论），不额外清理点赞行；
     * 不通知原作者——后台治理动作对作者表现为「内容消失」，多发一条通知反而放大违规内容的传播。</p>
     *
     * @param postId         动态ID
     * @param operatorUserId 管理员ID，仅用于日志追溯
     */
    void removePostByAdmin(Long postId, Long operatorUserId);

    /**
     * 分页查询某个用户点赞过的动态，按点赞时间倒序。
     *
     * @param userId        目标用户ID（当前只允许查询本人，由调用方传入认证上下文中的ID）
     * @param listQueryInfo 分页参数
     * @return 动态分页结果
     */
    PostPageVO queryMyLikedPostPage(Long userId, ListQueryDTO listQueryInfo);
}
