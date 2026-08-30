package org.dromara.gz.recycle.domain.bo;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 过期未核销单批量释放参数（GZ-RECYCLE-017，甲方 8.28）。
 *
 * <p>对应 {@code POST /system/gz/recycle/appointment/batch-release-expired}。
 * 逐单独立处理（一条失败不影响其余），返回 {@code succeeded / skipped / failed} 三元计数。</p>
 *
 * <p>{@code ids} 上限 500：一次点太多会让单个请求跑很久，前端「全选」又极易一次勾上全部历史积压单。
 * 500 对店员的实际清理动线绰绰有余（截图现场是 3 条），超了分批点即可。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-017)
 */
@Data
public class GzRecycleBatchReleaseBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 待释放的预约单 id 列表 */
    @NotEmpty(message = "请至少选择一条过期预约")
    @Size(max = 500, message = "一次最多释放 500 条，请分批操作")
    private List<Long> ids;
}
