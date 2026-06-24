package org.dromara.gz.gacha.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 投放线 admin 展示对象（GZ-GACHA-101，ADR-0013 改为投放线，列表项 + 详情共用）。
 *
 * <p>字段口径权威：doc/11 §7.2。id / machineId / productId / imageId 用 {@code ToStringSerializer} 转 string
 * （跨层契约 #1）。weight 原始整数，仅后台驱动抽奖归一化，不对 C 端展示（ADR-0013）。</p>
 *
 * <p><b>ADR-0013 join 字段</b>：{@code productName} / {@code imageId} / {@code referenceValueCent} 来自产品库
 * （{@code gz_gacha_product}）运行时 join 回填（service 用 mapByIds 批量取，禁 N+1）；线本身只存
 * {@code productId} / {@code rarity} / {@code weight} / 库存 / {@code enabled}。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-101 / ADR-0013)
 */
@Data
public class GzGachaPrizeVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 投放线主键（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 归属机器 id（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long machineId;

    /** 投放产品 id（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    /** 业务码 PRZ-yyyyMMdd-6位序号 */
    private String prizeNo;

    /** 产品名（join 产品库回填） */
    private String productName;

    /** 产品图 file_id（join 产品库回填；string，前端换签名 URL） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long imageId;

    /** 稀有度 SSR/SR/R/N（按机器可调，线本身字段） */
    private String rarity;

    /** 概率权重整数（线本身字段） */
    private Integer weight;

    /** 初始库存（线本身字段） */
    private Integer stockInitial;

    /** 剩余库存（线本身字段） */
    private Integer stockRemain;

    /** 公示参考价（分，null = 不显示；join 产品库回填） */
    private Long referenceValueCent;

    /** 0临时下架/1参与抽奖（线本身字段） */
    private Integer enabled;

    /** 乐观锁版本 */
    private Integer version;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;

    /** 备注 */
    private String remark;
}
