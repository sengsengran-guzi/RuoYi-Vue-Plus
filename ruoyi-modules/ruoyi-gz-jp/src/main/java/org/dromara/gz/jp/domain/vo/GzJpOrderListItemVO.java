package org.dromara.gz.jp.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单列表卡 VO（GZ-JP-105；mp UI:mp.order_list 消费）。
 *
 * <p>列表<b>不下发完整商品行</b>（一单可能 30 款，列表页渲染不了也不需要）——
 * 只给前 N 张缩略图 + 款数 + 实付。要看逐行履约进度点进详情。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-105)
 */
@Data
public class GzJpOrderListItemVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 订单主键（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 订单号 JPO-yyyyMMdd-6位 */
    private String orderNo;

    /** 订单总额（分）。★ 无运费 */
    private Long totalAmountCent;

    /** 订单状态 code（字典 gz_jp_order_status） */
    private String businessStatus;

    /** 订单状态中文 */
    private String businessStatusLabel;

    /** 款数（行数） */
    private Integer itemCount;

    /** 总件数（Σ qty） */
    private Integer totalQty;

    /** 前 N 张商品缩略图可渲染 URL（默认最多 4 张，无图回落占位图；恒非 null） */
    private List<String> thumbUrls;

    /** 下单时间 */
    private LocalDateTime createTime;

    /** 支付时间（未支付为 null） */
    private LocalDateTime paidTime;
}
