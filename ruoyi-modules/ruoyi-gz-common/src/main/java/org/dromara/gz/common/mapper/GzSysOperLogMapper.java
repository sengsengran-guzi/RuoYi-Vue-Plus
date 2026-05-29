package org.dromara.gz.common.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.common.domain.GzSysOperLog;
import org.dromara.gz.common.domain.vo.GzOperLogVO;

/**
 * GZ-SYS-006 操作日志数据层（独立映射 sys_oper_log，不依赖 ruoyi-system 模块）。
 *
 * <p>仅 SELECT + DELETE（写日志由 ruoyi 自带 LogAspect / OperLogEvent 完成 — AOP @Log 自动）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-006)
 */
public interface GzSysOperLogMapper extends BaseMapperPlus<GzSysOperLog, GzOperLogVO> {
}
