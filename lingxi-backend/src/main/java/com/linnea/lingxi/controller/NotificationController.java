package com.linnea.lingxi.controller;

import com.linnea.lingxi.common.web.Response;
import com.linnea.lingxi.dto.NotificationQueryDTO;
import com.linnea.lingxi.security.LoginUser;
import com.linnea.lingxi.service.NotificationService;
import com.linnea.lingxi.vo.NotificationPageVO;
import com.linnea.lingxi.vo.UnreadCountVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息通知接口。
 *
 * <p>所有端点都只操作「当前登录用户自己的通知」：路径里没有 userId，
 * 越权在 SQL 的 {@code WHERE user_id = ?} 处被拦住，不存在「改别人通知」的入口。</p>
 */
@Tag(name = "消息通知", description = "通知列表、未读数、标记已读")
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    /** 通知业务服务。 */
    private final NotificationService notificationService;

    /**
     * 分页查询我的通知。
     *
     * @param queryInfo 查询参数（游标、条数、是否只看未读）
     * @param loginUser 当前登录用户
     * @return 通知分页结果
     */
    @Operation(summary = "通知列表", description = "按通知时间倒序，键集分页；unreadOnly=true 只看未读；同时返回未读总数")
    @GetMapping
    public Response<NotificationPageVO> queryNotificationPage(@Valid NotificationQueryDTO queryInfo,
                                                              @AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点
        long startMillis = System.currentTimeMillis();
        log.info("GET /api/v1/notifications 开始, userId={}, unreadOnly={}, size={}",
                loginUser.userId(), queryInfo.getUnreadOnly(), queryInfo.getSize());
        // 步骤2：调用业务层查询通知列表
        NotificationPageVO notificationPage = notificationService.queryNotificationPage(loginUser.userId(), queryInfo);
        // 步骤3：记录出口结果与耗时
        log.info("GET /api/v1/notifications 完成, userId={}, itemCount={}, hasMore={}, unreadCount={}, cost={} ms",
                loginUser.userId(), notificationPage.getNotificationList().size(),
                notificationPage.getHasMore(), notificationPage.getUnreadCount(),
                System.currentTimeMillis() - startMillis);
        return Response.success(notificationPage);
    }

    /**
     * 查询我的未读通知数。
     *
     * @param loginUser 当前登录用户
     * @return 未读数
     */
    @Operation(summary = "未读通知数", description = "只返回一个数字，供角标轮询；比拉整页列表省流量")
    @GetMapping("/unread-count")
    public Response<UnreadCountVO> queryUnreadCount(@AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点
        long startMillis = System.currentTimeMillis();
        log.info("GET /api/v1/notifications/unread-count 开始, userId={}", loginUser.userId());
        // 步骤2：调用业务层统计未读
        UnreadCountVO unreadCountInfo = notificationService.queryUnreadCount(loginUser.userId());
        // 步骤3：记录出口结果与耗时
        log.info("GET /api/v1/notifications/unread-count 完成, userId={}, unreadCount={}, cost={} ms",
                loginUser.userId(), unreadCountInfo.getUnreadCount(), System.currentTimeMillis() - startMillis);
        return Response.success(unreadCountInfo);
    }

    /**
     * 把单条通知标记为已读。
     *
     * @param notificationId 通知ID
     * @param loginUser      当前登录用户
     * @return 最新未读数
     */
    @Operation(summary = "标记单条通知已读",
            description = "幂等：通知不存在、不属于本人、已读过都返回成功；返回最新未读数供覆盖角标")
    @PostMapping("/{notificationId}/read")
    public Response<UnreadCountVO> markRead(@PathVariable("notificationId") Long notificationId,
                                            @AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点
        long startMillis = System.currentTimeMillis();
        log.info("POST /api/v1/notifications/{}/read 开始, userId={}", notificationId, loginUser.userId());
        // 步骤2：调用业务层标记已读
        UnreadCountVO unreadCountInfo = notificationService.markRead(notificationId, loginUser.userId());
        // 步骤3：记录出口结果与耗时
        log.info("POST /api/v1/notifications/{}/read 完成, userId={}, unreadCount={}, cost={} ms",
                notificationId, loginUser.userId(), unreadCountInfo.getUnreadCount(),
                System.currentTimeMillis() - startMillis);
        return Response.success(unreadCountInfo);
    }

    /**
     * 把我的全部未读通知标记为已读。
     *
     * @param loginUser 当前登录用户
     * @return 最新未读数（正常为 0）
     */
    @Operation(summary = "全部标记已读", description = "一键清空角标；返回最新未读数")
    @PostMapping("/read-all")
    public Response<UnreadCountVO> markAllRead(@AuthenticationPrincipal LoginUser loginUser) {
        // 步骤1：记录请求入口与耗时起点
        long startMillis = System.currentTimeMillis();
        log.info("POST /api/v1/notifications/read-all 开始, userId={}", loginUser.userId());
        // 步骤2：调用业务层批量标记已读
        UnreadCountVO unreadCountInfo = notificationService.markAllRead(loginUser.userId());
        // 步骤3：记录出口结果与耗时
        log.info("POST /api/v1/notifications/read-all 完成, userId={}, unreadCount={}, cost={} ms",
                loginUser.userId(), unreadCountInfo.getUnreadCount(), System.currentTimeMillis() - startMillis);
        return Response.success(unreadCountInfo);
    }
}
