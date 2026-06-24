package org.dromara.gz.gacha.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 产品库 admin 展示对象（ADR-0013 / GZ-GACHA-112，列表项 + 详情 + 奖品池下拉选项共用）。
 *
 * <p>跨层契约（CLAUDE.md #1）：{@code id} / {@code imageId} 用 {@link ToStringSerializer} 转 string
 * （避免 JS long 精度丢失）；金额 {@code reference_value_cent} 分单位，前端 /100 显示元。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-112)
 */
@Data
public class GzGachaProductVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 产品主键（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 业务码 GPRD-yyyyMMdd-6位序号 */
    private String productNo;

    /** 产品名 */
    private String name;

    /** 产品图 file_id（string；前端换签名 URL） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long imageId;

    /** 公示参考价（分，null = 不显示；前端 /100 显示元） */
    private Long referenceValueCent;

    /** IP 标签 */
    private String ipTag;

    /** 1 可投放 / 0 停用 */
    private Integer enabled;

    /** 乐观锁版本（前端编辑回传，避免覆盖并发改动） */
    private Integer version;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;

    /** 备注 */
    private String remark;
}
