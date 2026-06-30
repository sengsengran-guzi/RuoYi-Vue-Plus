package org.dromara.gz.bean.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.gz.bean.domain.entity.GzBeanSeatClosure;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * gz_bean_seat_closure 视图对象（admin 配置回显，GZ-BEAN-036 Req3）。
 *
 * <p>跨层契约 #1：id / storeId / seatId 用 {@code ToStringSerializer} 转 string（防 JS long 精度丢失）。
 * {@code storeName} / {@code seatNo} 由 Service enrich 回填，admin 列表直接展示门店名 + 座位号。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-036)
 */
@Data
@AutoMapper(target = GzBeanSeatClosure.class)
public class GzBeanSeatClosureVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 门店 id（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    /** 被关闭的具体座位 id（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long seatId;

    /** ISO 星期 1=Mon..7=Sun */
    private Integer weekday;

    /** 关闭时段起（含） */
    @JsonFormat(pattern = "HH:mm")
    private LocalTime timeStart;

    /** 关闭时段止（不含） */
    @JsonFormat(pattern = "HH:mm")
    private LocalTime timeEnd;

    /** 0=停用 / 1=生效 */
    private Integer enabled;

    /** 创建时间（公共字段） */
    private LocalDateTime createTime;

    /** 备注 */
    private String remark;

    /** 门店名（Service enrich 回填；AutoMapper 无对应 entity 字段不参与映射） */
    private String storeName;

    /** 座位号（Service 由 seatId join gz_bean_seat.seat_no 回填） */
    private String seatNo;
}
