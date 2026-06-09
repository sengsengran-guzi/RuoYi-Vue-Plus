package org.dromara.gz.gacha.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Date;

/**
 * 扭蛋机 admin 展示对象（GZ-GACHA-101，列表项 + 详情共用）。
 *
 * <p>字段口径权威：doc/11 §7.1。id / coverImageId 用 {@code ToStringSerializer} 转 string（跨层契约 #1，
 * 避免 JS long 精度丢失）；金额 *_cent 分单位，前端 /100 显示元。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-101)
 */
@Data
public class GzGachaMachineVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 机器主键（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 业务码 GM-yyyyMMdd-6位序号 */
    private String machineNo;

    /** 机器名 */
    private String name;

    /** 封面 file_id（string；前端换签名 URL） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long coverImageId;

    /** 单抽价（分；前端 /100 显示元） */
    private Long singlePriceCent;

    /** 十连价（分，null = 不支持十连） */
    private Long tenPackPriceCent;

    /** IP 标签 */
    private String ipTag;

    /** 状态 on_shelf/off_shelf/auto_off */
    private String status;

    /** 上架时间 */
    private LocalDateTime onlineTime;

    /** 计划下架时间 */
    private LocalDateTime offlineTime;

    /** 累计抽奖次数 */
    private Long salesCount;

    /** 奖品池奖品数（详情/列表辅助统计；列表场景按需填充） */
    private Long prizeCount;

    /** 乐观锁版本（前端编辑回传，避免覆盖并发改动） */
    private Integer version;

    /** 创建时间（BaseEntity 提供，java.util.Date） */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;

    /** 备注 */
    private String remark;
}
