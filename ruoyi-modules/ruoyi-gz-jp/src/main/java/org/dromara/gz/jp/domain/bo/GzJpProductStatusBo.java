package org.dromara.gz.jp.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 商品<b>批量上下架</b>入参（GZ-JP-102，UI:admin.product「支持多选批量上下架」）。
 *
 * <p>状态流转只能走本端点（{@code POST /system/gz/jp/product/status}），不允许通过普通 PUT 改 status
 * —— 与 GZ-JP-101 场的 open / close 端点同一口径。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-102)
 */
@Data
public class GzJpProductStatusBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 待改状态的商品主键集合（前端多选） */
    @NotEmpty(message = "请至少选择一个商品")
    private List<Long> ids;

    /** 目标状态 on_shelf / off_shelf（service 校验合法性，非法值直接拒绝而非静默回落） */
    @NotBlank(message = "目标状态不能为空")
    private String status;
}
