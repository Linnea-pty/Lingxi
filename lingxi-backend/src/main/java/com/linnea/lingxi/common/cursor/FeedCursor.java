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
 * 信息流键集分页游标：把排序键 (是否置顶, 创建时间, 动态ID) 编码成一个不透明字符串。
 *
 * <p>WHY: 信息流排序键是三列组合，若让前端分别回传三个参数，任何一项缺失或错位都会造成
 * 翻页重复或漏数据；编码成单串后前端只负责原样回传，服务端解码失败即判参数错误。</p>
 *
 * <p>编码格式：Base64URL（无填充）包装 "pinned|createTime(ISO_LOCAL_DATE_TIME)|postId"。
 * 该串不是加密，也不承载敏感信息，仅供分页定位使用。</p>
 */
@Slf4j
@Getter
public final class FeedCursor {

    /** 游标字段分隔符：使用竖线，避免与 ISO 时间格式中的冒号、横线冲突。 */
    private static final String FIELD_SEPARATOR = "|";

    /** 游标字段个数：置顶标记、创建时间、动态ID 三段，缺一不可。 */
    private static final int FIELD_COUNT = 3;

    /** 置顶标记：1-置顶，与 DDL 中 is_pinned 取值一致。 */
    private static final int PINNED_YES = 1;

    /** 置顶标记：0-未置顶，与 DDL 中 is_pinned 取值一致。 */
    private static final int PINNED_NO = 0;

    /** 游标中的置顶标记：0-未置顶 1-置顶，对应排序键第一列。 */
    private final Integer pinned;

    /** 游标中的创建时间，对应排序键第二列，精度到秒。 */
    private final LocalDateTime createTime;

    /** 游标中的动态ID，对应排序键第三列，保证排序唯一稳定。 */
    private final Long postId;

    /**
     * 构造游标，仅供本类的静态编解码方法调用。
     *
     * @param pinned     置顶标记，0 或 1
     * @param createTime 创建时间
     * @param postId     动态ID
     */
    private FeedCursor(Integer pinned, LocalDateTime createTime, Long postId) {
        this.pinned = pinned;
        this.createTime = createTime;
        this.postId = postId;
    }

    /**
     * 编码游标，供上一页结果回传给前端。
     *
     * @param pinned     置顶标记，0-未置顶 1-置顶
     * @param createTime 该行创建时间，不可为空
     * @param postId     该行动态ID，不可为空
     * @return Base64URL 编码后的游标字符串
     */
    public static String encode(Integer pinned, LocalDateTime createTime, Long postId) {
        // 步骤1：按固定格式拼接三段明文
        String rawCursor = pinned + FIELD_SEPARATOR
                + createTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + FIELD_SEPARATOR + postId;
        // 步骤2：URL 安全 Base64 编码，去掉填充符以免出现在 URL 中需要转义
        String encodedCursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(rawCursor.getBytes(StandardCharsets.UTF_8));
        log.info("信息流游标编码完成, pinned={}, createTime={}, postId={}", pinned, createTime, postId);
        return encodedCursor;
    }

    /**
     * 解码前端回传的游标。
     *
     * @param encodedCursor Base64URL 游标，为空表示查询第一页
     * @return 解码后的游标；入参为空时返回 null 表示首页
     */
    public static FeedCursor decode(String encodedCursor) {
        // 步骤1：未携带游标即首页查询，无需解码
        if (!StringUtils.hasText(encodedCursor)) {
            log.info("未携带信息流游标, 按第一页查询");
            return null;
        }
        // 步骤2：Base64 解码后按分隔符切三段，任一段非法都视为客户端提交了无效游标
        try {
            String rawCursor = new String(Base64.getUrlDecoder().decode(encodedCursor), StandardCharsets.UTF_8);
            String[] cursorFields = rawCursor.split("\\" + FIELD_SEPARATOR, FIELD_COUNT);
            if (cursorFields.length != FIELD_COUNT) {
                throw new IllegalArgumentException("游标分段数不是 " + FIELD_COUNT);
            }
            int cursorPinned = Integer.parseInt(cursorFields[0]);
            // 步骤3：只接受 DDL 允许的置顶取值，其余视为伪造游标
            if (cursorPinned != PINNED_YES && cursorPinned != PINNED_NO) {
                throw new IllegalArgumentException("游标置顶标记非法: " + cursorPinned);
            }
            LocalDateTime cursorCreateTime = LocalDateTime.parse(cursorFields[1],
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            long cursorPostId = Long.parseLong(cursorFields[2]);
            log.info("信息流游标解码完成, pinned={}, createTime={}, postId={}",
                    cursorPinned, cursorCreateTime, cursorPostId);
            return new FeedCursor(cursorPinned, cursorCreateTime, cursorPostId);
        } catch (RuntimeException decodeException) {
            log.warn("信息流游标解码失败, 按参数错误处理, cursorLength={}", encodedCursor.length(), decodeException);
            throw new BusinessException(ErrorCode.COMMON_PARAM_ERROR, "分页游标格式不正确，请回传上一页返回的 nextCursor");
        }
    }
}
