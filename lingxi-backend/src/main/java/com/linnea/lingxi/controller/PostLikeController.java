package com.linnea.lingxi.controller;

import com.linnea.lingxi.common.web.Response;
import com.linnea.lingxi.dto.ListQueryDTO;
import com.linnea.lingxi.security.LoginUser;
import com.linnea.lingxi.service.PostLikeService;
import com.linnea.lingxi.vo.LikeResultVO;
import com.linnea.lingxi.vo.LikeUserPageVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
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
 * 点赞接口：对指定动态点赞与取消点赞。
 *
 * <p>点赞人身份取自认证上下文，路径只传动态ID，从结构上避免「替他人点赞」。
 * 两个接口都幂等：重复点赞/重复取消返回当前状态而不是报错。</p>
 */
@Tag(name = "点赞", description = "动态点赞、取消点赞与点赞人列表")
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/posts/{postId}")
@RequiredArgsConstructor
public class PostLikeController {

    /** 点赞业务服务。 */
    private final PostLikeService postLikeService;

    /**
     * 点赞。
     *
     * @param postId    被点赞动态ID
     * @param loginUser 当前调用者，由认证过滤器写入 SecurityContext 后注入
     * @return 操作后的点赞状态与总数
     */
    @Operation(summary = "点赞", description = "对指定动态点赞；已点赞时幂等返回当前状态与最新总数")
    @PostMapping("/like")
    public Response<LikeResultVO> likePost(
            @PathVariable @Positive(message = "动态ID必须为正数") Long postId,
            @AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点
        long startMillis = System.currentTimeMillis();
        log.info("POST /api/v1/posts/{}/like 开始, userId={}", postId, loginUser.userId());
        // 步骤2：调用业务层点赞
        LikeResultVO likeResult = postLikeService.likePost(postId, loginUser.userId());
        // 步骤3：记录出口结果与耗时
        log.info("POST /api/v1/posts/{}/like 完成, liked={}, likeCount={}, cost={} ms",
                postId, likeResult.getLiked(), likeResult.getLikeCount(), System.currentTimeMillis() - startMillis);
        return Response.success(likeResult);
    }

    /**
     * 取消点赞。
     *
     * @param postId    被取消动态ID
     * @param loginUser 当前调用者，由认证过滤器写入 SecurityContext 后注入
     * @return 操作后的点赞状态与总数
     */
    @Operation(summary = "取消点赞", description = "取消对指定动态的点赞；未点赞时幂等返回当前状态与最新总数")
    @DeleteMapping("/like")
    public Response<LikeResultVO> cancelLike(
            @PathVariable @Positive(message = "动态ID必须为正数") Long postId,
            @AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点
        long startMillis = System.currentTimeMillis();
        log.info("DELETE /api/v1/posts/{}/like 开始, userId={}", postId, loginUser.userId());
        // 步骤2：调用业务层取消点赞
        LikeResultVO likeResult = postLikeService.cancelLike(postId, loginUser.userId());
        // 步骤3：记录出口结果与耗时
        log.info("DELETE /api/v1/posts/{}/like 完成, liked={}, likeCount={}, cost={} ms",
                postId, likeResult.getLiked(), likeResult.getLikeCount(), System.currentTimeMillis() - startMillis);
        return Response.success(likeResult);
    }

    /**
     * 查询点赞人列表。
     *
     * @param postId        动态ID
     * @param listQueryInfo 分页参数：游标 + 每页条数
     * @return 点赞人分页结果，按点赞时间倒序
     */
    @Operation(summary = "点赞人列表",
            description = "按点赞时间倒序，键集分页，回传 nextCursor 取下一页；注销账号不出现在列表中")
    @GetMapping("/likes")
    public Response<LikeUserPageVO> queryLikeUserPage(
            @PathVariable @Positive(message = "动态ID必须为正数") Long postId,
            @Valid ListQueryDTO listQueryInfo) {
        // 步骤1：记录请求入口与耗时起点
        long startMillis = System.currentTimeMillis();
        log.info("GET /api/v1/posts/{}/likes 开始, size={}", postId, listQueryInfo.getSize());
        // 步骤2：调用业务层查询
        LikeUserPageVO likeUserPage = postLikeService.queryLikeUserPage(postId, listQueryInfo);
        // 步骤3：记录出口结果与耗时
        log.info("GET /api/v1/posts/{}/likes 完成, itemCount={}, hasMore={}, cost={} ms",
                postId, likeUserPage.getUserList() == null ? 0 : likeUserPage.getUserList().size(),
                likeUserPage.getHasMore(), System.currentTimeMillis() - startMillis);
        return Response.success(likeUserPage);
    }
}
