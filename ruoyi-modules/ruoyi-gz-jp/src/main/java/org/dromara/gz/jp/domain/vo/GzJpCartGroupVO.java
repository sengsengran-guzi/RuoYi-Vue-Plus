package org.dromara.gz.jp.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 购物车「按场分组」的一组（GZ-JP-104，UI:mp.cart.group）。
 *
 * <p>★ <b>跨场商品共存于同一购物车</b>（field-ssot 的 gz_jp_cart_item 段 + UI:mp.cart.group 明示），
 * 同一订单也可含多场商品（GZ-JP-105）。所以这里是「一车 N 组」，不是「一车一场」。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-104)
 */
@Data
public class GzJpCartGroupVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 场主键。
     *
     * <p><b>可能为 null</b> —— 商品已被删除的项归不到任何场，统一进「孤儿组」排在最后
     * （{@code eventName} = 「已失效商品」）。前端渲染分组头时要判空。</p>
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long eventId;

    /** 场编号 EVT-yyyyMMdd-6位（孤儿组为 null） */
    private String eventNo;

    /** 场名称（分组头展示；孤儿组为「已失效商品」） */
    private String eventName;

    /**
     * 场生效状态（{@code open} / {@code closed}；孤儿组或场已删为 null）。
     *
     * <p>读时惰性判定的结果，与 {@code GzJpEventMpVO.status} 同一口径。
     * <b>但「能不能勾选下单」请看 {@link #eventBookable} 与每项的 {@code invalid}</b>，别自己解析状态串。</p>
     */
    private String eventStatus;

    /**
     * ★ 本场此刻能否下单（{@code IGzJpEventService.isBookable} 的结果）。
     *
     * <p>{@code false} → 本组所有项都是失效项（{@code invalid=true, invalidReason=event_closed}），
     * 分组头可整组显示「本场已结束」。恒非 null。</p>
     */
    private Boolean eventBookable;

    /** 本组的购物车项（最近加购在前） */
    private List<GzJpCartItemVO> items;
}
