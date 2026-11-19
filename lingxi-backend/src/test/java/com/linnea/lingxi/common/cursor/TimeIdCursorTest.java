package com.linnea.lingxi.common.cursor;

import com.linnea.lingxi.common.exception.BusinessException;
import com.linnea.lingxi.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TimeIdCursor} 单元测试：验证游标编解码的往返一致性与非法输入的统一错误码。
 *
 * <p>游标对前端不透明，一旦解析失败必须转成业务参数错误，不能把 Base64/日期解析异常暴露出去。</p>
 */
class TimeIdCursorTest {

    /** 测试用创建时间，含纳秒以确认精度不被截断。 */
    private static final LocalDateTime CREATE_TIME = LocalDateTime.of(2026, 11, 17, 10, 30, 15, 123_000_000);

    /** 测试用主键ID。 */
    private static final Long RECORD_ID = 9527L;

    @Test
    @DisplayName("编码后解码：时间与主键完全还原")
    void encodeThenDecode_roundTrip() {
        // 执行
        String encodedCursor = TimeIdCursor.encode(CREATE_TIME, RECORD_ID);
        TimeIdCursor decodedCursor = TimeIdCursor.decode(encodedCursor);

        // 断言
        assertThat(decodedCursor).isNotNull();
        assertThat(decodedCursor.getCreateTime()).isEqualTo(CREATE_TIME);
        assertThat(decodedCursor.getId()).isEqualTo(RECORD_ID);
    }

    @Test
    @DisplayName("游标为 null 或空白：返回 null 表示首页查询")
    void decode_blankCursor_returnsNull() {
        // 断言
        assertThat(TimeIdCursor.decode(null)).isNull();
        assertThat(TimeIdCursor.decode("")).isNull();
        assertThat(TimeIdCursor.decode("   ")).isNull();
    }

    @Test
    @DisplayName("非法 Base64：转成参数错误码，不暴露底层异常")
    void decode_invalidBase64_throwsParamError() {
        // 执行 + 断言
        assertThatThrownBy(() -> TimeIdCursor.decode("not-a-base64!!"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.COMMON_PARAM_ERROR.getCode());
    }

    @Test
    @DisplayName("分段数不足：转成参数错误码")
    void decode_missingField_throwsParamError() {
        // 前置：只编码了时间一段
        String brokenCursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("2026-11-17T10:30:15".getBytes(StandardCharsets.UTF_8));

        // 执行 + 断言
        assertThatThrownBy(() -> TimeIdCursor.decode(brokenCursor))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.COMMON_PARAM_ERROR.getCode());
    }

    @Test
    @DisplayName("主键非数字：转成参数错误码")
    void decode_nonNumericId_throwsParamError() {
        // 前置：第二段不是数字
        String brokenCursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("2026-11-17T10:30:15|abc".getBytes(StandardCharsets.UTF_8));

        // 执行 + 断言
        assertThatThrownBy(() -> TimeIdCursor.decode(brokenCursor))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.COMMON_PARAM_ERROR.getCode());
    }
}
