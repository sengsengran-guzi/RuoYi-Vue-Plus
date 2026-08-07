package org.dromara.gz.jp.domain.bo;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * admin 订单管理列表查询条件（GZ-JP-109，UI:admin.order 顶部筛选）。
 *
 * <p>字段全部可选；分页走 ruoyi {@code PageQuery}。</p>
 *
 * <p><b>★ 与履约看板（{@code GzJpFulfillQueryBo}）口径不同，别混用</b>：
 * 看板是<b>店员采购视角</b>，硬编码「只出付过款的订单的行」；
 * 本查询是<b>资金视角的查单页</b>，{@code created}（待支付）与 {@code cancelled}（已取消）
 * 的订单<b>必须能查到</b> —— 客人来问「我下的单怎么没了」时，
 * 店员要能看到那张没付成功的单，所以这里没有、也不该有付款态过滤。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-109)
 */
@Data
public class GzJpOrderQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 订单号<b>模糊</b>匹配（含 = LIKE %?%）。
     *
     * <p>刻意不做精确匹配：真实场景是客人在电话里报「后面几位是 000123」，
     * 店员记不住 {@code JPO-20260807-} 这个前缀。拼团单量级不大，不为这点索引效率
     * 牺牲查单页最主要的使用方式。</p>
     */
    private String orderNo;

    /** 精确客人（gz_user.id）。与 {@link #keyword} 同时给时取交集 */
    private Long userId;

    /** 客人模糊搜索（昵称 / openid，交给 gz-common 的用户检索收口，不在本模块写跨表 JOIN） */
    private String keyword;

    /**
     * 订单状态多选（字典 {@code gz_jp_order_status}）；空 = 全部。
     *
     * <p>未知值<b>不静默丢弃</b>而是直接报错 —— 丢弃会让筛选结果看起来「更多」而不是「更少」，
     * 店员会以为自己筛错了条件（同 GZ-JP-106 看板的处理）。</p>
     */
    private List<String> businessStatus;

    /** 下单时间起（含当日 00:00:00） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate beginDate;

    /** 下单时间止（含当日 23:59:59） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;
}
