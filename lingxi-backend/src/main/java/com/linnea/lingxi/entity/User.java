package com.linnea.lingxi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表实体，对应 lingxi.user。
 *
 * <p>主键由数据库自增分配（{@link IdType#AUTO}）。逻辑删除列 is_deleted 由全局配置
 * logic-delete-field=isDeleted 统一接管：查询自动追加 is_deleted = 0，删除语句自动改写为 UPDATE。</p>
 *
 * <p>安全约束：本类仅供数据访问层使用，禁止直接作为接口出参返回，
 * password_hash、api_key 属敏感字段，对外必须走 VO 转换。</p>
 */
@Data
@TableName("user")
public class User {

    /** 主键ID，数据库自增。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录用户名：全局唯一，注销后不释放（同名不可复用）；比较区分大小写（列级 as_cs），写入前只做 trim 与字符白名单校验，不做大小写归一。 */
    private String username;

    /** 密码哈希（加盐后的密文），禁止存放或回显明文。 */
    private String passwordHash;

    /** 昵称，用于页面展示，可与用户名不同且允许重复。 */
    private String nickname;

    /** 头像图片地址，空串表示未设置。 */
    private String avatarUrl;

    /** 角色：USER-普通用户 ADMIN-管理员，取值见 {@link com.linnea.lingxi.common.enums.UserRole}；只在认证签发时决定授予的权限。 */
    private String role;

    /** 账号状态：1-正常 0-封禁，取值见 {@link com.linnea.lingxi.common.enums.UserStatus}；封禁后每个请求回查时被拦下。 */
    private Integer status;

    /** 封禁原因（运营备注），空串表示未封禁；解封时清空。 */
    private String banReason;

    /** Agent 密钥的 SHA-256 摘要（明文只在签发响应中出现一次），空串表示未开通 Agent 访问；属敏感字段，禁止回显。 */
    private String apiKey;

    /** 逻辑删除标记：0-未删除 1-已删除，由 MyBatis-Plus 自动维护，业务代码不应手工赋值。 */
    @TableLogic
    private Integer isDeleted;

    /** 创建时间（业务本地时区），由数据库默认值填入。 */
    private LocalDateTime createTime;

    /** 更新时间（业务本地时区），由数据库 ON UPDATE 维护。 */
    private LocalDateTime updateTime;
}
