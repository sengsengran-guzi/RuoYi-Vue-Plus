package org.dromara.gz.jp.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 批量履约操作里<b>被拒的单行</b>（GZ-JP-106）。
 *
 * <p>admin 拿它把看板上对应行标红并写明原因，而不是弹一句「操作失败」。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-106)
 */
@Data
public class GzJpFulfillRejectVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 被拒的订单商品行 id（★ string，Java long 直传 JS 会丢精度） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long itemId;

    /** 该行当前状态（行不存在时为 null） */
    private String currentStatus;

    /** 当前状态中文（前端不必再维护一份映射） */
    private String currentStatusLabel;

    /** 拒绝原因码（{@code GzJpFulfillRejectReason} 的 name，前端可按码分支） */
    private String reasonCode;

    /** 拒绝原因中文（可直接展示） */
    private String reason;
}
