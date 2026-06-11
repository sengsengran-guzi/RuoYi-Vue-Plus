package org.dromara.gz.common.recycle.service;

import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.recycle.domain.vo.RecycleBinItemVO;

/**
 * 回收站 service（GZ-ADMIN-108）—— 跨业务表软删（del_flag='2'）聚合治理。
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-108)
 */
public interface IGzRecycleBinService {

    /**
     * 聚合查询各业务表 del_flag='2' 记录（service 内存合并分页，V1.1 数据量小不建物理 VIEW）。
     *
     * @param entityType  实体类型（空 = 全部注册类型）
     * @param nameKeyword 名称模糊（可空）
     * @param startTime   删除时间范围起（可空，yyyy-MM-dd HH:mm:ss）
     * @param endTime     删除时间范围止（可空）
     * @param pageNum     页码（从 1 起）
     * @param pageSize    页大小
     */
    TableDataInfo<RecycleBinItemVO> list(String entityType, String nameKeyword,
                                         String startTime, String endTime,
                                         int pageNum, int pageSize);

    /**
     * 恢复：del_flag '2' → '0'。影响 0 行（记录不存在/非已删）→ 抛业务异常（不静默成功，D6）。
     *
     * @return 恢复记录的展示名称（写操作日志用）
     */
    String restore(String entityType, Long entityId);

    /**
     * 立即归档：archived_flag=1（del_flag 保持 '2'）。影响 0 行 → 抛业务异常。
     *
     * @return 归档记录的展示名称
     */
    String archive(String entityType, Long entityId);

    /**
     * 物理清理超期已归档记录（del_flag='2' AND archived_flag=1 AND update_time &lt; NOW−30 天）。
     * <p>SnailJob cron 调用，TenantHelper.ignore 下执行。</p>
     *
     * @return 物理删除总行数
     */
    int cleanupExpired();
}
