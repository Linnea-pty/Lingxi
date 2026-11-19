package com.linnea.lingxi.controller;

import com.linnea.lingxi.common.web.Response;
import com.linnea.lingxi.dto.ListQueryDTO;
import com.linnea.lingxi.dto.UpdateProfileDTO;
import com.linnea.lingxi.security.LoginUser;
import com.linnea.lingxi.service.CommentService;
import com.linnea.lingxi.service.PostService;
import com.linnea.lingxi.service.UserService;
import com.linnea.lingxi.vo.CommentPageVO;
import com.linnea.lingxi.vo.PostPageVO;
import com.linnea.lingxi.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 个人中心接口：当前用户自己的点赞与评论记录。
 *
 * <p>路径固定为 {@code /users/me} 而不是 {@code /users/{userId}}：当前只允许查本人，
 * 用 me 把「越权查他人记录」从路由结构上排除，后续若要开放他人主页再单独加受控接口。</p>
 */
@Tag(name = "个人中心", description = "我的点赞与我的评论")
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserCenterController {

    /** 动态业务服务：查我赞过的动态。 */
    private final PostService postService;

    /** 评论业务服务：查我的评论。 */
    private final CommentService commentService;

    /** 用户业务服务：改资料与上传头像。 */
    private final UserService userService;

    /**
     * 修改我的资料（当前只支持昵称）。
     *
     * @param updateInfo 修改请求参数
     * @param loginUser  当前登录用户
     * @return 修改后的用户信息
     */
    @Operation(summary = "修改我的资料",
            description = "只支持改昵称（2~20 字符）；头像走上传接口，用户名不可改（它是登录凭据）")
    @PutMapping
    public Response<UserVO> updateProfile(@RequestBody @Valid UpdateProfileDTO updateInfo,
                                          @AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点（昵称是用户输入，仅记录长度避免日志注入）
        long startMillis = System.currentTimeMillis();
        log.info("PUT /api/v1/users/me 开始, userId={}, nicknameLength={}",
                loginUser.userId(), updateInfo.getNickname() == null ? 0 : updateInfo.getNickname().length());
        // 步骤2：调用业务层更新昵称
        UserVO updatedUserInfo = userService.updateProfile(loginUser.userId(), updateInfo);
        // 步骤3：记录出口结果与耗时
        log.info("PUT /api/v1/users/me 完成, userId={}, cost={} ms",
                loginUser.userId(), System.currentTimeMillis() - startMillis);
        return Response.success(updatedUserInfo);
    }

    /**
     * 上传并更新我的头像。
     *
     * @param avatarFile 头像文件，表单字段名固定为 file
     * @param loginUser  当前登录用户
     * @return 修改后的用户信息（avatarUrl 为新地址）
     */
    @Operation(summary = "上传我的头像",
            description = "multipart/form-data，字段名 file；仅支持 jpg/png/gif/webp，≤2MB；返回最新资料")
    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response<UserVO> uploadAvatar(@RequestPart("file") MultipartFile avatarFile,
                                         @AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点（不记录原始文件名：它由客户端提供，可能含换行造成日志注入）
        long startMillis = System.currentTimeMillis();
        log.info("POST /api/v1/users/me/avatar 开始, userId={}, contentType={}, size={} bytes",
                loginUser.userId(), avatarFile.getContentType(), avatarFile.getSize());
        // 步骤2：调用业务层校验、落盘并更新头像地址
        UserVO updatedUserInfo = userService.updateAvatar(loginUser.userId(), avatarFile);
        // 步骤3：记录出口结果与耗时
        log.info("POST /api/v1/users/me/avatar 完成, userId={}, avatarUrl={}, cost={} ms",
                loginUser.userId(), updatedUserInfo.getAvatarUrl(), System.currentTimeMillis() - startMillis);
        return Response.success(updatedUserInfo);
    }

    /**
     * 查询我赞过的动态列表。
     *
     * @param listQueryInfo 分页参数：游标 + 每页条数
     * @param loginUser     当前调用者
     * @return 动态分页结果，按点赞时间倒序
     */
    @Operation(summary = "我赞过的动态",
            description = "按点赞时间倒序，键集分页，回传 nextCursor 取下一页；动态被删除后不再出现在列表中")
    @GetMapping("/likes")
    public Response<PostPageVO> queryMyLikedPostPage(@Valid ListQueryDTO listQueryInfo,
                                                     @AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点
        long startMillis = System.currentTimeMillis();
        log.info("GET /api/v1/users/me/likes 开始, userId={}, size={}", loginUser.userId(), listQueryInfo.getSize());
        // 步骤2：调用业务层查询本人点赞记录
        PostPageVO likedPostPage = postService.queryMyLikedPostPage(loginUser.userId(), listQueryInfo);
        // 步骤3：记录出口结果与耗时
        log.info("GET /api/v1/users/me/likes 完成, userId={}, itemCount={}, hasMore={}, cost={} ms",
                loginUser.userId(), likedPostPage.getPostList() == null ? 0 : likedPostPage.getPostList().size(),
                likedPostPage.getHasMore(), System.currentTimeMillis() - startMillis);
        return Response.success(likedPostPage);
    }

    /**
     * 查询我的评论列表。
     *
     * @param listQueryInfo 分页参数：游标 + 每页条数
     * @param loginUser     当前调用者
     * @return 评论分页结果，按评论时间倒序
     */
    @Operation(summary = "我的评论",
            description = "按评论时间倒序，键集分页；所属动态已删除的评论不返回")
    @GetMapping("/comments")
    public Response<CommentPageVO> queryMyCommentPage(@Valid ListQueryDTO listQueryInfo,
                                                      @AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点
        long startMillis = System.currentTimeMillis();
        log.info("GET /api/v1/users/me/comments 开始, userId={}, size={}", loginUser.userId(), listQueryInfo.getSize());
        // 步骤2：调用业务层查询本人评论
        CommentPageVO commentPage = commentService.queryMyCommentPage(loginUser.userId(), listQueryInfo);
        // 步骤3：记录出口结果与耗时
        log.info("GET /api/v1/users/me/comments 完成, userId={}, itemCount={}, hasMore={}, cost={} ms",
                loginUser.userId(), commentPage.getCommentList() == null ? 0 : commentPage.getCommentList().size(),
                commentPage.getHasMore(), System.currentTimeMillis() - startMillis);
        return Response.success(commentPage);
    }
}
