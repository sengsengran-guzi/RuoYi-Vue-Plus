package org.dromara.gz.recycle.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 回收物品对象 VO（ADR-0012 §2 / 契约 15a §B.2 / §E.1，单份多选对象形态）。
 *
 * <p>由 {@code product_snapshot_json} 反序列化（mp 顾客详情 + admin/店员详情共用）。语义从旧「明细列表」改为
 * <b>单物品对象</b>：品类多选 + IP 多选（id + 名称快照）+ 自定义 IP + 数量桶（code + label 快照）。</p>
 *
 * <p><b>兼容旧数组数据</b>（V1.1 多明细 JSON 数组）：parseProducts 探测根节点，数组根投影为本对象
 * （categories=去重各行 category / customIps=去重各行非空 ip / qtyBucketCode 留空）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004/T4)
 */
@Data
public class GzRecycleProductVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 品类多选（字典 gz_recycle_category value） */
    private List<String> categories;

    /** 主数据 IP id 快照（序列化为 string，跨层契约 #1） */
    @JsonSerialize(contentUsing = ToStringSerializer.class)
    private List<Long> ipIds;

    /** IP 中文名快照（admin/store 端免再 join；旧数据从行级 ip 投影） */
    private List<String> ipNames;

    /** 用户自定义 IP 文本 */
    private List<String> customIps;

    /** 数量桶 code（gz_recycle_qty_range.code；旧数据无桶时为 null） */
    private String qtyBucketCode;

    /** 数量桶展示文案快照（如 25-50 件；旧数据为 null） */
    private String qtyBucketLabel;

    /**
     * 【已退休 GZ-RECYCLE-012】提交时冻结的「是否额外占用下一档」快照（1=是 / 0=否）。
     *
     * <p>小时格模型下占格面由 {@link #spanHours} 表达，本字段仅保留用于反序列化 012 之前的老单
     * （删掉会让老单 JSON 解析丢字段）。新单不再写入。</p>
     */
    private Integer occupyNextSlot;

    /**
     * 提交时冻结的<b>占用小时数</b>（= {@code ceil(gz_recycle_qty_range.duration_minutes / 60)}，
     * GZ-RECYCLE-012 / ADR-0022）。
     *
     * <p><b>占格面在提交那一刻定死</b>：此后点数档被禁用 / 被改时长都不得改变既有单的占用面 ——
     * 改期重算 span 时以本快照为准，而<b>绝不</b>去活查点数档表。活查是超卖入口：禁用某个点数档
     * 是正常运营动作，一旦活查，此后任何对该档既有大单的改期都会把后续小时静默放开，
     * 与顾客实际到店时长物理双占。</p>
     *
     * <p>老单（012 之前提交）为 {@code null}，改期时退回
     * 「{@code matched_duration_minutes} → 当前区间宽度」的兜底链，见
     * {@code GzRecycleAppointmentServiceImpl#resolveSpanHoursForExisting}。</p>
     */
    private Integer spanHours;
}
