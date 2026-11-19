package com.linnea.lingxi.controller;

import com.linnea.lingxi.common.web.Response;
import com.linnea.lingxi.dto.CommentQueryDTO;
import com.linnea.lingxi.dto.CreateCommentDTO;
import com.linnea.lingxi.security.LoginUser;
import com.linnea.lingxi.service.CommentService;
import com.linnea.lingxi.vo.CommentPageVO;
import com.linnea.lingxi.vo.CommentVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评论接口：对指定动态发表评论与查询一级评论列表。
 *
 * <p>评论人身份取自认证上下文；动态归属来自路径；讨论串根ID与被回复人由服务端推导，
 * 前端只需要在回复时传 parentId。</p>
 */
@Tag(name = "评论", description = "发表评论与一级评论列表")
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/posts/{postId}/comments")
@RequiredArgsConstructor
public class CommentController {

    /** 评论业务服务。 */
    private final CommentService commentService;

    /**
     * 发表评论或回复。
     *
     * @param postId             所属动态ID
     * @param createCommentInfo  评论请求参数（正文 + 可选父评论ID）
     * @param loginUser          当前调用者，由认证过滤器写入 SecurityContext 后注入
     * @return 新建评论的对外信息
     */
    @Operation(summary = "发表评论",
            description = "parentId 为 0 或不传表示一级评论；大于 0 表示回复该条评论，讨论串根ID由服务端继承；"
                    + "带 requestId 时同一用户同一键重复提交只落一条评论并返回首次结果")
    @PostMapping
    public Response<CommentVO> createComment(
            @PathVariable @Positive(message = "动态ID必须为正数") Long postId,
            @Valid @RequestBody CreateCommentDTO createCommentInfo,
            @AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点，正文内容不写日志
        long startMillis = System.currentTimeMillis();
        log.info("POST /api/v1/posts/{}/comments 开始, userId={}, parentId={}, requestId={}",
                postId, loginUser.userId(), createCommentInfo.getParentId(), createCommentInfo.getRequestId());
        // 步骤2：调用业务层发表评论
        CommentVO createdCommentInfo = commentService.createComment(createCommentInfo, postId, loginUser.userId());
        // 步骤3：记录出口结果与耗时
        log.info("POST /api/v1/posts/{}/comments 完成, commentId={}, rootId={}, cost={} ms",
                postId, createdCommentInfo.getCommentId(), createdCommentInfo.getRootId(),
                System.currentTimeMillis() - startMillis);
        return Response.success(createdCommentInfo);
    }

    /**
     * 查询一级评论列表。
     *
     * @param postId           动态ID
     * @param commentQueryInfo 分页参数：游标 + 每页条数
     * @return 一级评论分页结果，含每条评论的回复数
     */
    @Operation(summary = "一级评论列表",
            description = "按发布时间倒序，键集分页，回传 nextCursor 取下一页；每条附带该讨论串的回复数")
    @GetMapping
    public Response<CommentPageVO> queryRootCommentPage(
            @PathVariable @Positive(message = "动态ID必须为正数") Long postId,
            @Valid CommentQueryDTO commentQueryInfo) {
        // 步骤1：记录请求入口与耗时起点
        long startMillis = System.currentTimeMillis();
        log.info("GET /api/v1/posts/{}/comments 开始, size={}", postId, commentQueryInfo.getSize());
        // 步骤2：调用业务层查询
        CommentPageVO commentPage = commentService.queryRootCommentPage(postId, commentQueryInfo);
        // 步骤3：记录出口结果与耗时
        log.info("GET /api/v1/posts/{}/comments 完成, itemCount={}, hasMore={}, cost={} ms",
                postId, commentPage.getCommentList() == null ? 0 : commentPage.getCommentList().size(),
                commentPage.getHasMore(), System.currentTimeMillis() - startMillis);
        return Response.success(commentPage);
    }
}
