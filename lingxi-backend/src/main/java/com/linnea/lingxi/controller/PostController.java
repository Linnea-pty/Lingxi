package com.linnea.lingxi.controller;

import com.linnea.lingxi.common.web.Response;
import com.linnea.lingxi.dto.CreatePostDTO;
import com.linnea.lingxi.dto.FeedQueryDTO;
import com.linnea.lingxi.dto.UpdatePostDTO;
import com.linnea.lingxi.security.LoginUser;
import com.linnea.lingxi.service.PostService;
import com.linnea.lingxi.vo.FeedPageVO;
import com.linnea.lingxi.vo.PostVO;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 动态接口：发布动态与信息流浏览。
 *
 * <p>两个接口都在安全链的认证范围内，需携带访问令牌，或 Agent 的 {@code X-Api-Key}；
 * 发布者身份取自过滤器写入的 {@link LoginUser}（含 agent 标记），不从请求体读取。</p>
 */
@Tag(name = "动态", description = "发布、修改、删除动态与信息流浏览")
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/posts")
@RequiredArgsConstructor
public class PostController {

    /** 动态业务服务。 */
    private final PostService postService;

    /**
     * 发布一条动态。
     *
     * @param createPostInfo 发布请求参数（正文 + 可选幂等键）
     * @param loginUser      当前调用者，由认证过滤器写入 SecurityContext 后注入
     * @return 发布后的动态信息
     */
    @Operation(summary = "发布动态",
            description = "Bearer 令牌发布记为 web 且不置顶；X-Api-Key 代发记为 agent 且置顶。"
                    + "携带 requestId 时同一幂等键重复提交返回首次结果")
    @PostMapping
    public Response<PostVO> createPost(@Valid @RequestBody CreatePostDTO createPostInfo,
                                       @AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点，正文内容不写日志
        long startMillis = System.currentTimeMillis();
        log.info("POST /api/v1/posts 开始, userId={}, agent={}, hasRequestId={}",
                loginUser.userId(), loginUser.agent(), createPostInfo.getRequestId() != null);
        // 步骤2：调用业务层，身份标记决定来源与置顶
        PostVO createdPostInfo = postService.createPost(createPostInfo, loginUser.userId(), loginUser.agent());
        // 步骤3：记录出口结果与耗时
        log.info("POST /api/v1/posts 完成, postId={}, userId={}, cost={} ms",
                createdPostInfo.getPostId(), loginUser.userId(), System.currentTimeMillis() - startMillis);
        return Response.success(createdPostInfo);
    }

    /**
     * 查询信息流（键集分页）。
     *
     * @param feedQueryInfo 分页参数：游标 + 每页条数
     * @return 当前页动态与下一页游标
     */
    @Operation(summary = "信息流", description = "全局信息流，按 置顶 → 发布时间 倒序；键集分页，回传 nextCursor 取下一页")
    @GetMapping("/feed")
    public Response<FeedPageVO> queryFeed(@Valid FeedQueryDTO feedQueryInfo) {
        // 步骤1：记录请求入口与耗时起点，只记录游标长度不记录游标明文
        long startMillis = System.currentTimeMillis();
        int cursorLength = feedQueryInfo.getCursor() == null ? 0 : feedQueryInfo.getCursor().length();
        log.info("GET /api/v1/posts/feed 开始, size={}, cursorLength={}", feedQueryInfo.getSize(), cursorLength);
        // 步骤2：调用业务层
        FeedPageVO feedPage = postService.queryFeed(feedQueryInfo);
        // 步骤3：记录出口结果与耗时
        log.info("GET /api/v1/posts/feed 完成, itemCount={}, hasMore={}, cost={} ms",
                feedPage.getPostList() == null ? 0 : feedPage.getPostList().size(), feedPage.getHasMore(),
                System.currentTimeMillis() - startMillis);
        return Response.success(feedPage);
    }

    /**
     * 修改动态正文。
     *
     * @param postId         动态ID
     * @param updatePostInfo 修改请求参数（仅正文）
     * @param loginUser      当前调用者
     * @return 修改后的动态信息
     */
    @Operation(summary = "修改动态", description = "仅作者可改，只允许修改正文；来源与置顶不可改")
    @PutMapping("/{postId}")
    public Response<PostVO> updatePost(
            @PathVariable @Positive(message = "动态ID必须为正数") Long postId,
            @Valid @RequestBody UpdatePostDTO updatePostInfo,
            @AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点，正文内容不写日志
        long startMillis = System.currentTimeMillis();
        log.info("PUT /api/v1/posts/{} 开始, userId={}", postId, loginUser.userId());
        // 步骤2：调用业务层修改
        PostVO updatedPostInfo = postService.updatePost(postId, updatePostInfo, loginUser.userId());
        // 步骤3：记录出口结果与耗时
        log.info("PUT /api/v1/posts/{} 完成, userId={}, cost={} ms",
                postId, loginUser.userId(), System.currentTimeMillis() - startMillis);
        return Response.success(updatedPostInfo);
    }

    /**
     * 删除动态。
     *
     * @param postId    动态ID
     * @param loginUser 当前调用者
     * @return 空响应，业务成功与否看 code
     */
    @Operation(summary = "删除动态", description = "仅作者可删；逻辑删除动态并级联删除其下全部评论")
    @DeleteMapping("/{postId}")
    public Response<Void> deletePost(
            @PathVariable @Positive(message = "动态ID必须为正数") Long postId,
            @AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点
        long startMillis = System.currentTimeMillis();
        log.info("DELETE /api/v1/posts/{} 开始, userId={}", postId, loginUser.userId());
        // 步骤2：调用业务层删除
        postService.deletePost(postId, loginUser.userId());
        // 步骤3：记录出口结果与耗时
        log.info("DELETE /api/v1/posts/{} 完成, userId={}, cost={} ms",
                postId, loginUser.userId(), System.currentTimeMillis() - startMillis);
        return Response.success(null);
    }
}
