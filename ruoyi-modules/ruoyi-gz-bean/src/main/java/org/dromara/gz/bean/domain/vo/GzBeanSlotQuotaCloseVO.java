package org.dromara.gz.bean.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.gz.bean.domain.entity.GzBeanSlotQuotaClose;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * gz_bean_slot_quota_close 视图对象（admin 配置回显 / 列表，客户 0702 反馈 #4a）。
 *
 * <p>跨层契约 #1：id / storeId / seatTypeConfigId 用 {@code ToStringSerializer} 转 string（防 JS long 精度丢失）。</p>
 *
 * @author kevin-coder (sensenran-guzi · 客户 0702 反馈 #4a)
 */
@Data
@AutoMapper(target = GzBeanSlotQuotaClose.class)
public class GzBeanSlotQuotaCloseVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 门店 id（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    /** 桌型档 id（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long seatTypeConfigId;

    /** 服务日 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate sessDate;

    /** 关闭作用的 1h 格起整点 */
    @JsonFormat(pattern = "HH:mm")
    private LocalTime slotStart;

    /** 该格关闭配额个数 */
    private Integer closeCount;

    /** 创建时间（公共字段） */
    private LocalDateTime createTime;

    /** 备注 */
    private String remark;
}
