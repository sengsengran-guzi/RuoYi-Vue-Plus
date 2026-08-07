package org.dromara.gz.jp.domain.bo;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * 履约看板列表查询条件（GZ-JP-106 提供，供 GZ-JP-108 消费；UI:admin.fulfill_board 顶部筛选）。
 *
 * <p>字段全部可选；分页走 ruoyi {@code PageQuery}。<b>返回的行恒按客人聚簇</b>
 * （{@code ORDER BY user_id, order_id, item_id}），前端按 {@code userId} 断组即可。</p>
 *
 * <p><b>本查询已内置「只看付过款的订单」</b>（{@code GzJpOrderStatus.isPaidLike}），
 * 不可关闭 —— 未支付订单的行 {@code fulfill_status} 也是 {@code purchasing}，
 * 下发给店员就等于让人拿没付钱的单去日本下单。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-106)
 */
@Data
public class GzJpFulfillQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 精确客人（gz_user.id）。与 {@link #keyword} 同时给时取交集 */
    private Long userId;

    /** 客人模糊搜索（昵称 / 手机号 / 用户编号，交给 gz-common 的用户检索收口） */
    private String keyword;

    /** 履约状态多选（字典 gz_jp_fulfill_status）；空 = 全部。未知值不静默丢弃，直接判空结果 */
    private List<String> fulfillStatus;

    /** 所属场（按商品反查）；空 = 全部场 */
    private Long eventId;

    /** 订单号精确 */
    private String orderNo;

    /** 运单号精确（按包裹回看这一票发了哪些行） */
    private String trackingNo;

    /** 下单时间起（含当日 00:00:00） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate beginDate;

    /** 下单时间止（含当日 23:59:59） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;
}
