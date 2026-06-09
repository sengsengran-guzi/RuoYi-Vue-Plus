package org.dromara.gz.user.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;
import org.apache.ibatis.type.Alias;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 统一订单聚合 VO（GZ-USER-101，GET /app/gz/user/orders）—— 跨预购 + 扭蛋两类业务订单的逻辑视图。
 *
 * <p><b>字段口径权威</b>：doc/11 §8.1「VO 字段对齐表」+ D10 README §1。doc/11 表格按文档惯例写 snake_case
 * 列名，落地以本项目跨层契约（全 camelCase JSON，与 gz-ord / gz-gacha 现有 VO 一致）为准 —— wire 字段名 =
 * Java 字段名（Jackson 默认），语义集逐字对齐 §8.1（不增不减字段）。mp 端按本字段名消费。</p>
 *
 * <p><b>多态 product snapshot</b>（决策 D3）：preorder 分支 {@code productSnapshotJson} 透传
 * {@code gz_ord_order.product_snapshot_json} 原结构；gacha 分支由 service 把
 * {@code prize_snapshot_json} + {@code machine_snapshot_json} 合并成恰好 5 字段
 * {@code {name, cover, spec, machine, rarity}}（doc/11 §8.1 gacha 合并规则）后序列化进本字段。
 * 图片字段 {@code cover} 透传 {@code image_id}（FK gz_file_object），<b>不解析裸 URL</b>（前端换签名 URL，AC5）。</p>
 *
 * <p><b>状态不翻译</b>（AC8/AC9）：{@code businessStatus} / {@code logisticsStatus} 原样透传各业务侧 enum 字符串，
 * UI 状态映射由 mp 端按 doc/11 §8.2 自行渲染。{@code cnCarrierCode} 仅 {@code in_china_dispatching}(L2) 后非空。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-101)
 */
@Data
@Builder
@Alias("GzUserUnifiedOrderVo")
public class GzUnifiedOrderVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 订单业务码（preorder: PREORD-... / gacha: GACHA-...）；前端跳详情用 orderNo（不暴露 id） */
    private String orderNo;

    /** 业务类型字面量：preorder / gacha（mp tab 切换 + biz chip） */
    private String businessType;

    /** 归属用户 id（string，防 JS 精度丢失） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /**
     * 商品 snapshot（JSON 文本，前端按 businessType 分支渲染）。
     * preorder：透传 gz_ord_order.product_snapshot_json 原结构；
     * gacha：service 合并出 {name, cover(image_id), spec, machine, rarity} 5 字段。
     */
    private String productSnapshotJson;

    /** 订单总额（分；前端 / 100 显示元，不在接口层转元，AC4 强约束） */
    private Long totalAmountCent;

    /** 业务态（原样透传，AC8）：preorder created/paid/in_logistics/delivered/cancelled/refunded；gacha pending_ship/in_logistics/delivered/refunded */
    private String businessStatus;

    /** 物流态（原样透传 C1 枚举，AC9）：in_japan / in_china_dispatching / delivered */
    private String logisticsStatus;

    /** 国内快递公司编码（字典 gz_express_carrier；仅 in_china_dispatching 后非空，原样透传不翻译） */
    private String cnCarrierCode;

    /** 国内快递单号（仅 in_china_dispatching 后非空） */
    private String cnTrackingNo;

    /** 地址 snapshot（JSON 文本；gacha 未补地址前可能为 null） */
    private String addressSnapshotJson;

    /** 支付时间 */
    private LocalDateTime paidTime;

    /** 签收时间 */
    private LocalDateTime deliveredTime;

    /** 下单时间（= create_time；列表排序 + 显示锚点） */
    private LocalDateTime createdAt;
}
