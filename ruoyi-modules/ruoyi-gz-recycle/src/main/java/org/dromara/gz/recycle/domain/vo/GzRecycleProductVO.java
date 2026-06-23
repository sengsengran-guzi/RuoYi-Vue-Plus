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
}
