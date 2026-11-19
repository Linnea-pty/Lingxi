package com.linnea.lingxi.common.exception;

import lombok.Getter;

/**
 * 全局错误码：code 供前端分支判断，defaultMessage 为可直接展示给用户的中文兜底文案。
 *
 * <p>编码规则：COMMON_ 前缀为全局通用错误，业务模块用「模块名_」前缀（如 USER_）；
 * 禁止裸数字码；错误码一经发布不改语义，语义变更须新增码。</p>
 */
@Getter
public enum ErrorCode {

    /** 请求参数校验失败（Bean Validation 未通过）。 */
    COMMON_PARAM_ERROR("COMMON_PARAM_ERROR", "请求参数不合法"),

    /** 未认证：未携带令牌、令牌非法或已过期、令牌对应账号已注销。 */
    COMMON_UNAUTHORIZED("COMMON_UNAUTHORIZED", "未登录或登录状态已失效"),

    /** 已认证但无权限访问该资源。 */
    COMMON_FORBIDDEN("COMMON_FORBIDDEN", "无权访问该资源"),

    /** 系统级错误：基础设施异常或未预期异常，对外不暴露技术细节。 */
    COMMON_SYSTEM_ERROR("COMMON_SYSTEM_ERROR", "系统繁忙，请稍后重试"),

    /** 上传文件为空：未选择文件或文件长度为 0。 */
    FILE_UPLOAD_EMPTY("FILE_UPLOAD_EMPTY", "请选择要上传的图片"),

    /** 上传文件类型不在白名单内：按文件头判定的真实类型校验，不信任客户端给的扩展名与 Content-Type。 */
    FILE_TYPE_NOT_ALLOWED("FILE_TYPE_NOT_ALLOWED", "仅支持 JPG、PNG、GIF、WEBP 格式的图片"),

    /** 上传文件超出大小上限。 */
    FILE_TOO_LARGE("FILE_TOO_LARGE", "图片大小超出限制"),

    /** 用户名已被占用：含已注销账号占用的用户名（本系统用户名注销后不释放）。 */
    USER_NAME_DUPLICATED("USER_NAME_DUPLICATED", "该用户名已被占用，请更换一个"),

    /** 登录失败：为防用户名枚举，账号不存在与密码错误共用此码。 */
    USER_LOGIN_FAILED("USER_LOGIN_FAILED", "用户名或密码错误"),

    /** 用户不存在或已注销。 */
    USER_NOT_FOUND("USER_NOT_FOUND", "用户不存在或已注销"),

    /** 不能关注自己：自关注会让粉丝数/关注数失真，且没有业务含义。 */
    USER_FOLLOW_SELF("USER_FOLLOW_SELF", "不能关注自己"),

    /**
     * 账号已被封禁：登录、刷新令牌换发、以及已签发令牌的每次请求都返回此码。
     *
     * <p>与「密码错误」分开的原因：封禁是运营的显式处置，需要让用户知道该联系管理员，
     * 而不是反复尝试密码；它只在密码校验通过后返回，不具备用户名枚举价值。</p>
     */
    USER_BANNED("USER_BANNED", "账号已被封禁，请联系管理员"),

    /** 动态不存在或已被删除（含逻辑删除）。 */
    POST_NOT_FOUND("POST_NOT_FOUND", "动态不存在或已删除"),

    /** 评论不存在或已被删除（含逻辑删除）。 */
    COMMENT_NOT_FOUND("COMMENT_NOT_FOUND", "评论不存在或已删除"),

    /** 回复的父评论不属于目标动态：拒绝跨动态挂载，防止讨论串串台。 */
    COMMENT_PARENT_MISMATCH("COMMENT_PARENT_MISMATCH", "被回复的评论不属于该动态"),

    /** 相同幂等键的请求正在处理中：并发重复提交被拦下，客户端应稍后重试或复用首次结果。 */
    COMMON_DUPLICATE_REQUEST("COMMON_DUPLICATE_REQUEST", "相同请求正在处理中，请勿重复提交"),

    /**
     * 刷新令牌不可用：格式非法、类型不符、已过期、已被换发轮换或已登出。
     *
     * <p>与 COMMON_UNAUTHORIZED 分开：前端据此清空本地令牌并跳登录页，
     * 而不是继续用访问令牌重试。</p>
     */
    AUTH_REFRESH_TOKEN_INVALID("AUTH_REFRESH_TOKEN_INVALID", "刷新令牌无效或已失效，请重新登录");

    /** 错误码：英文大写 + 下划线，前端按此值判断，不可为数字码。 */
    private final String code;

    /** 默认提示文案：可直接展示给用户，可被具体业务场景覆盖。 */
    private final String defaultMessage;

    /**
     * 构造错误码。
     *
     * @param code           错误码标识，不可为空
     * @param defaultMessage 默认提示文案，不可为空
     */
    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }
}
