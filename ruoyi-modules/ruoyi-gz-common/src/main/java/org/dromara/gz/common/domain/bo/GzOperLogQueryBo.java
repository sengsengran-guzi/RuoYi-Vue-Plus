package org.dromara.gz.common.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * GZ-SYS-006 操作日志查询条件（admin GET /system/gz/oper-log/list）。
 *
 * <p>支持参数：</p>
 * <ul>
 *   <li>title — 操作模块模糊</li>
 *   <li>operName — 操作人员模糊</li>
 *   <li>operIp — 操作 IP 模糊</li>
 *   <li>businessType — 业务类型等值</li>
 *   <li>status — 操作状态等值</li>
 *   <li>params.beginTime / params.endTime — operTime 区间</li>
 * </ul>
 *
 * <p>staff 权限会**强制覆盖 operName** = 当前 LoginHelper.getUsername()，不允许越权查别人日志。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-006)
 */
@Data
public class GzOperLogQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 操作模块模糊 */
    private String title;

    /** 操作人员模糊（staff 角色调用 list 时被后端强制覆盖为当前用户名） */
    private String operName;

    /** 操作 IP 模糊 */
    private String operIp;

    /** 业务类型（0 其它 / 1 新增 / 2 修改 / 3 删除 / ...） */
    private Integer businessType;

    /** 操作状态（0 正常 / 1 异常） */
    private Integer status;

    /** 排序列（前端可传 operTime / costTime） */
    private String orderByColumn;

    /** 排序方向（asc / desc） */
    private String isAsc;

    /** addDateRange 注入的 beginTime / endTime — operTime 区间 */
    private Map<String, Object> params = new HashMap<>();
}
