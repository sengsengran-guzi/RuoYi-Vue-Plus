package org.dromara.gz.bean.domain.bo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * gz_bean_slot_quota_close admin 列表查询条件（客户 0702 反馈 #4a）。
 *
 * @author kevin-coder (sensenran-guzi · 客户 0702 反馈 #4a)
 */
@Data
public class GzBeanSlotQuotaCloseQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 ID（精确） */
    private Long storeId;

    /** 桌型档 ID（精确） */
    private Long seatTypeConfigId;

    /** 服务日（精确） */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate sessDate;
}
