package org.dromara.gz.common.pay.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * gz_pay_shipping_order — 微信发货信息上报任务表 entity。<b>一笔支付单一行</b>。
 *
 * <p><b>虚拟商品</b>（拼豆）：支付成功后落 {@code pending} 行（与支付确认同事务），由 {@code @Async}
 * 即时上报 + 兜底重试推进到 {@code success}。消除微信支付完成页「未接入购物订单与卡包」提示。</p>
 *
 * <p><b>实物商品</b>（拼团）：<b>店员点发货那一刻</b>才落行 —— 支付当下没有任何可上报的发货事实。
 * 同一支付单陆续发多个包裹时<b>复用同一行</b>：{@link #shippingListJson} 累加包裹、
 * {@link #isAllDelivered} 在最后一批发出时置 true、{@link #uploadStatus} 重置回 {@code pending}
 * 触发重新上报（每次都把<b>累计</b>清单整份报给微信）。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_pay_shipping_order")
public class GzPayShippingOrder extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 待上报。 */
    public static final String STATUS_PENDING = "pending";
    /** 已上报成功。 */
    public static final String STATUS_SUCCESS = "success";
    /** 上报失败待重试。 */
    public static final String STATUS_FAILED = "failed";
    /**
     * 上报被微信侧终态拒绝，<b>停止自动重试</b>（{@code 10060002} 已完成发货 / {@code 10060003} 已用掉重新发货机会）。
     *
     * <p>不并入 {@code failed} 的原因：cron 与「手动补报」都按 {@code pending/failed} 扫，
     * 继续重试只会把每笔单<b>仅有一次</b>的「重新发货」机会烧掉、然后永远撞 {@code 10060003}。
     * 本状态的单需人工去小程序后台核对，admin 列表仍可对单条点「补报」强制再试一次。</p>
     */
    public static final String STATUS_BLOCKED = "blocked";

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 微信支付单号（幂等键）— UNIQUE(tenant_id, transaction_id） */
    private String transactionId;

    /** 业务订单号（溯源 gz_pay_transaction） */
    private String outTradeNo;

    /** preorder / gacha / pindou / test */
    private String businessType;

    /** 支付用户 openid */
    private String openid;

    /**
     * 本笔交易所属小程序 clientid（{@code wx.miniapp.apps} 的 key）；空串 = 走 {@code default-client-id}。
     *
     * <p>★ 多小程序的命门：access_token 是 appid 维度凭证，上报又发生在<b>没有请求上下文</b>的
     * {@code @Async} / cron / admin 补报线程里。不把归属存下来，拼团的单就会拿现小程序的 token 上报，
     * 微信恒回「支付单不存在」且重试永远好不了。</p>
     */
    private String clientId;

    /** 物流模式 1实体/2同城/3虚拟商品/4自提（拼豆=3 虚拟 / 拼团=1 实体） */
    private Integer logisticsType;

    /** 发货模式 1统一发货 / 2分拆发货（{@code ShippingInfo.DELIVERY_MODE_*}） */
    private Integer deliveryMode;

    /** 分拆发货时：截至目前整单是否已全部发完（统一发货为 null）。只有 true 才会触发微信「发货完成」通知 */
    private Boolean isAllDelivered;

    /** 商品描述（微信订单中心展示；实物件为整单摘要，真正上报的是各包裹自己的描述） */
    private String itemDesc;

    /**
     * 累计包裹清单 JSON（{@code List<ShippingPackage>}）—— 实物件专用，虚拟件为 null。
     *
     * <p>★ 存的是<b>截至目前的全部包裹</b>而不是最后一次新增的那个：微信文档没写明多次上报是覆盖还是
     * 追加合并，每次带全量在两种语义下结果都对。追加时按运单号去重。</p>
     */
    private String shippingListJson;

    /** 支付成功时间（48h 上报窗口锚点） */
    private LocalDateTime paidTime;

    /** pending / success / failed / blocked */
    private String uploadStatus;

    /** 上报尝试次数 */
    private Integer attemptCount;

    /** 最近一次失败原因 */
    private String lastError;

    /** 上报成功时间 */
    private LocalDateTime uploadedTime;

    /** 备注 */
    private String remark;

    /** 软删（0=正常 / 1=删除） */
    @TableLogic
    private String delFlag;
}
