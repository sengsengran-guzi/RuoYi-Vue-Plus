package org.dromara.gz.gacha.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 奖品 admin 展示对象（GZ-GACHA-101，奖品池列表项 + 详情共用）。
 *
 * <p>字段口径权威：doc/11 §7.2。id / machineId / imageId 用 {@code ToStringSerializer} 转 string
 * （跨层契约 #1）。weight 原始整数（前端展示「实时归一化概率」由 GACHA-103 算，本卡 admin 只展示权重）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-101)
 */
@Data
public class GzGachaPrizeVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 奖品主键（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 归属机器 id（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long machineId;

    /** 业务码 PRZ-yyyyMMdd-6位序号 */
    private String prizeNo;

    /** 奖品名 */
    private String name;

    /** 奖品图 file_id（string；前端换签名 URL） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long imageId;

    /** 稀有度 SSR/SR/R/N */
    private String rarity;

    /** 概率权重整数 */
    private Integer weight;

    /** 初始库存 */
    private Integer stockInitial;

    /** 剩余库存 */
    private Integer stockRemain;

    /** 公示参考价（分，null = 不显示） */
    private Long referenceValueCent;

    /** 0临时下架/1参与抽奖 */
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
