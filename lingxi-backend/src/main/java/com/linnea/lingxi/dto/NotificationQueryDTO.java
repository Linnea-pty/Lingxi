package com.linnea.lingxi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 通知列表查询参数：在通用键集分页参数上增加「只看未读」开关。
 *
 * <p>继承 {@link ListQueryDTO} 而不是重复声明 cursor/size：两者的校验规则
 * （游标长度 ≤256、条数 1~30 缺省 10）必须完全一致，复制一份迟早漂移。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "通知列表查询参数")
public class NotificationQueryDTO extends ListQueryDTO {

    /** 是否只返回未读通知，缺省 false（返回全部）。 */
    @Schema(description = "是否只返回未读通知，缺省 false（返回全部）", example = "false", defaultValue = "false")
    private Boolean unreadOnly;
}
