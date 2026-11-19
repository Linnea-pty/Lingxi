package com.linnea.lingxi.controller;

import com.linnea.lingxi.common.web.Response;
import com.linnea.lingxi.dto.CommentQueryDTO;
import com.linnea.lingxi.security.LoginUser;
import com.linnea.lingxi.service.CommentService;
import com.linnea.lingxi.vo.CommentPageVO;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评论读删接口：按根评论ID读取整个讨论串下的回复，以及删除单条评论。
 *
 * <p>单独成接口而不是塞进一级评论列表：讨论串回复可能很多，全部内联会导致列表响应膨胀；
 * 读接口无身份依赖，仅需登录态。</p>
 *
 * <p>删除放在同一控制器：两者都以 {@code /api/v1/comments} 为前缀，
 * 拆成两个类会出现同一资源前缀挂两个控制器，后续加接口容易撞路径。</p>
 */
@Tag(name = "评论", description = "讨论串回复列表与删除评论")
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/comments")
@RequiredArgsConstructor
public class CommentReplyController {

    /** 评论业务服务。 */
    private final CommentService commentService;

    /**
     * 查询讨论串回复列表。
     *
     * @param rootCommentId    讨论串根评论ID
     * @param commentQueryInfo 分页参数：游标 + 每页条数
     * @return 回复分页结果，按时间正序
     */
    @Operation(summary = "讨论串回复列表",
            description = "按发布时间正序（符合对话阅读顺序），键集分页，回传 nextCursor 取下一页")
    @GetMapping("/{rootCommentId}/replies")
    public Response<CommentPageVO> queryReplyPage(
            @PathVariable @Positive(message = "根评论ID必须为正数") Long rootCommentId,
            @Valid CommentQueryDTO commentQueryInfo) {
        // 步骤1：记录请求入口与耗时起点
        long startMillis = System.currentTimeMillis();
        log.info("GET /api/v1/comments/{}/replies 开始, size={}", rootCommentId, commentQueryInfo.getSize());
        // 步骤2：调用业务层查询
        CommentPageVO replyPage = commentService.queryReplyPage(rootCommentId, commentQueryInfo);
        // 步骤3：记录出口结果与耗时
        log.info("GET /api/v1/comments/{}/replies 完成, itemCount={}, hasMore={}, cost={} ms",
                rootCommentId, replyPage.getCommentList() == null ? 0 : replyPage.getCommentList().size(),
                replyPage.getHasMore(), System.currentTimeMillis() - startMillis);
        return Response.success(replyPage);
    }

    /**
     * 删除一条评论（连同其下全部回复）。
     *
     * @param commentId 评论ID
     * @param loginUser 当前调用者
     * @return 空响应，业务成功与否看 code
     */
    @Operation(summary = "删除评论",
            description = "仅作者可删；逻辑删除该评论及其全部后代回复，避免留下孤儿回复")
    @DeleteMapping("/{commentId}")
    public Response<Void> deleteComment(
            @PathVariable @Positive(message = "评论ID必须为正数") Long commentId,
            @AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点
        long startMillis = System.currentTimeMillis();
        log.info("DELETE /api/v1/comments/{} 开始, userId={}", commentId, loginUser.userId());
        // 步骤2：调用业务层删除
        commentService.deleteComment(commentId, loginUser.userId());
        // 步骤3：记录出口结果与耗时
        log.info("DELETE /api/v1/comments/{} 完成, userId={}, cost={} ms",
                commentId, loginUser.userId(), System.currentTimeMillis() - startMillis);
        return Response.success(null);
    }
}
