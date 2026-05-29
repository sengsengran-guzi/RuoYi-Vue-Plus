package org.dromara.gz.common.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.domain.bo.GzOperLogQueryBo;
import org.dromara.gz.common.domain.vo.GzOperLogVO;

import java.util.List;

/**
 * GZ-SYS-006 操作日志查询 / 维护服务（admin 端）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-006)
 */
public interface IGzOperLogService {

    /**
     * 分页查询。owner / staff 权限差异化由 Controller 层在传入 query 前注入完成
     * （staff 强制 operName=当前用户名）— Service 只按 query 字面查。
     */
    TableDataInfo<GzOperLogVO> selectPageList(GzOperLogQueryBo query, PageQuery pageQuery);

    /**
     * 全量列表查询（导出用）。
     */
    List<GzOperLogVO> selectList(GzOperLogQueryBo query);

    /**
     * 按 ID 详情查询。
     */
    GzOperLogVO selectById(Long operId);

    /**
     * 按 ID 批量软删（这里 sys_oper_log 无 del_flag — 实际是物理删）。
     */
    int deleteByIds(Long[] operIds);

    /**
     * 删除 N 月前的过期日志（LogRetentionJob 调，默认 12 个月）。
     *
     * @param retentionMonths 保留月数
     * @return 删除条数
     */
    int cleanOlderThan(int retentionMonths);
}
