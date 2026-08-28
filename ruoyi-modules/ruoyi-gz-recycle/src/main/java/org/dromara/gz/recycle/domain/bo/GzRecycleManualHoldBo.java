package org.dromara.gz.recycle.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * admin 手动占用时段提交参数（ADR-0021 §1，代客预约 + 临时关闭时段合一机制）。
 *
 * <p>对应 {@code POST /system/gz/recycle/appointment/manual-hold}。一次可选同门店同日多个到店时段格，
 * 同一事务 all-or-nothing 写入（任一格已被占 → 整批回滚，不留残行）。<b>无客户身份 / 无点数档 / 无金额 /
 * 无照片</b>——{@code remark} 是手动占用记录唯一的辨识信息，必填。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-010)
 */
@Data
public class GzRecycleManualHoldBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 id */
    @NotNull(message = "门店不能为空")
    private Long storeId;

    /** 占用日期 */
    @NotNull(message = "请选择占用日期")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate apptDate;

    /**
     * 占用的<b>小时格起点</b>列表（GZ-RECYCLE-012 / ADR-0022，可多选，同事务 all-or-nothing）。
     *
     * <p>一格一行，每行 span=1 小时 —— ADR-0021「要占两格就选两格建两行」的规则在小时格模型下更自然。
     * service 会先 {@code distinct().sorted()}：前端多选顺序不可信，乱序会与 submit 的升序加锁撞出死锁。</p>
     */
    @NotEmpty(message = "请至少选择一个时间")
    private List<LocalTime> slotStarts;

    /**
     * 备注（<b>必填</b>）：手动占用没有客户身份、没有点数档，备注是唯一辨识信息
     * （如「张老师电话预约 138xxxx」/「今天下午盘货不接单」）。
     */
    @NotBlank(message = "请填写占用备注")
    @Size(max = 500, message = "备注最多 500 字")
    private String remark;
}
