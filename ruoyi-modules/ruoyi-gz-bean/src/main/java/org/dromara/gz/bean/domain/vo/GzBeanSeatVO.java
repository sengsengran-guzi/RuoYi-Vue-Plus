package org.dromara.gz.bean.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.gz.bean.domain.entity.GzBeanSeat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * gz_bean_seat 视图对象（GZ-BEAN-002）。
 *
 * <p>字段权威：doc/11 §3.3。admin / mp 共用。
 * mp 端 BEAN-003 选座页消费时仅取 enabled=1 子集。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-002)
 */
@Data
@AutoMapper(target = GzBeanSeat.class)
public class GzBeanSeatVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long storeId;
    private String seatNo;
    private String rowLabel;
    private Integer colIndex;
    private Integer enabled;
    private Integer sortNo;
    private LocalDateTime createTime;
    private String remark;
}
