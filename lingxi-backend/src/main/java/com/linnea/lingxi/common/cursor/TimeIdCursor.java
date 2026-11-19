package com.linnea.lingxi.common.cursor;

import com.linnea.lingxi.common.exception.BusinessException;
import com.linnea.lingxi.common.exception.ErrorCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * 通用键集分页游标：把排序键 (创建时间, 主键ID) 编码成一个不透明字符串。
 *
 * <p>WHY 与 {@link FeedCursor} 分开：信息流排序键是三列（含置顶标记），评论列表只有两列；
 * 两种载荷不同，混用一个类会出现「有的游标带置顶位、有的不带」的分支判断。
 * 两者编码方式一致（Base64URL 包装竖线分隔的明文），前端都只需原样回传。</p>
 *
 * <p>编码格式：Base64URL（无填充）包装 "createTime(ISO_LOCAL_DATE_TIME)|id"。
 * 该串不是加密，也不承载敏感信息，仅供分页定位使用。</p>
 */
@Slf4j
@Getter
public final class TimeIdCursor {

    /** 游标字段分隔符：使用竖线，避免与 ISO 时间格式中的冒号、横线冲突。 */
    private static final String FIELD_SEPARATOR = "|";

    /** 游标字段个数：创建时间、主键ID 两段，缺一不可。 */
    private static final int FIELD_COUNT = 2;

    /** 游标中的创建时间，对应排序键第一列，精度到秒。 */
    private final LocalDateTime createTime;

    /** 游标中的主键ID，对应排序键第二列，保证排序唯一稳定。 */
    private final Long id;

    /**
     * 构造游标，仅供本类的静态编解码方法调用。
     *
     * @param createTime 创建时间
     * @param id         主键ID
     */
    private TimeIdCursor(LocalDateTime createTime, Long id) {
        this.createTime = createTime;
        this.id = id;
    }

    /**
     * 编码游标，供上一页结果回传给前端。
     *
     * @param createTime 该行创建时间，不可为空
     * @param id         该行主键ID，不可为空
     * @return Base64URL 编码后的游标字符串
     */
    public static String encode(LocalDateTime createTime, Long id) {
        // 步骤1：按固定格式拼接两段明文
        String rawCursor = createTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + FIELD_SEPARATOR + id;
        // 步骤2：URL 安全 Base64 编码，去掉填充符以免出现在 URL 中需要转义
        String encodedCursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(rawCursor.getBytes(StandardCharsets.UTF_8));
        log.info("键集游标编码完成, createTime={}, id={}", createTime, id);
        return encodedCursor;
    }

    /**
     * 解码前端回传的游标。
     *
     * @param encodedCursor Base64URL 游标，为空表示查询第一页
     * @return 解码后的游标；入参为空时返回 null 表示首页
     */
    public static TimeIdCursor decode(String encodedCursor) {
        // 步骤1：未携带游标即首页查询，无需解码
        if (!StringUtils.hasText(encodedCursor)) {
            log.info("未携带键集游标, 按第一页查询");
            return null;
        }
        // 步骤2：Base64 解码后按分隔符切两段，任一段非法都视为客户端提交了无效游标
        try {
            String rawCursor = new String(Base64.getUrlDecoder().decode(encodedCursor), StandardCharsets.UTF_8);
            String[] cursorFields = rawCursor.split("\\" + FIELD_SEPARATOR, FIELD_COUNT);
            if (cursorFields.length != FIELD_COUNT) {
                throw new IllegalArgumentException("游标分段数不是 " + FIELD_COUNT);
            }
            LocalDateTime cursorCreateTime = LocalDateTime.parse(cursorFields[0],
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            long cursorId = Long.parseLong(cursorFields[1]);
            log.info("键集游标解码完成, createTime={}, id={}", cursorCreateTime, cursorId);
            return new TimeIdCursor(cursorCreateTime, cursorId);
        } catch (RuntimeException decodeException) {
            log.warn("键集游标解码失败, 按参数错误处理, cursorLength={}", encodedCursor.length(), decodeException);
            throw new BusinessException(ErrorCode.COMMON_PARAM_ERROR, "分页游标格式不正确，请回传上一页返回的 nextCursor");
        }
    }
}
