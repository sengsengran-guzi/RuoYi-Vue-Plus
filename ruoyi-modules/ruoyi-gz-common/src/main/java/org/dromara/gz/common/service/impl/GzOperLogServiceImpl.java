package org.dromara.gz.common.service.impl;

import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.domain.GzSysOperLog;
import org.dromara.gz.common.domain.bo.GzOperLogQueryBo;
import org.dromara.gz.common.domain.vo.GzOperLogVO;
import org.dromara.gz.common.mapper.GzSysOperLogMapper;
import org.dromara.gz.common.service.IGzOperLogService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * GZ-SYS-006 操作日志服务实现。
 *
 * <p>读 sys_oper_log；写日志由 ruoyi 自带 LogAspect / OperLogEvent 完成（AOP @Log 自动）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-006)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzOperLogServiceImpl implements IGzOperLogService {

    private final GzSysOperLogMapper baseMapper;

    @Override
    public TableDataInfo<GzOperLogVO> selectPageList(GzOperLogQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzSysOperLog> lqw = buildQueryWrapper(query);
        if (StringUtils.isBlank(pageQuery.getOrderByColumn())) {
            lqw.orderByDesc(GzSysOperLog::getOperId);
        }
        Page<GzOperLogVO> page = baseMapper.selectVoPage(pageQuery.build(), lqw);
        return TableDataInfo.build(page);
    }

    @Override
    public List<GzOperLogVO> selectList(GzOperLogQueryBo query) {
        LambdaQueryWrapper<GzSysOperLog> lqw = buildQueryWrapper(query).orderByDesc(GzSysOperLog::getOperId);
        return baseMapper.selectVoList(lqw);
    }

    @Override
    public GzOperLogVO selectById(Long operId) {
        return baseMapper.selectVoById(operId);
    }

    @Override
    public int deleteByIds(Long[] operIds) {
        if (ArrayUtil.isEmpty(operIds)) {
            return 0;
        }
        return baseMapper.deleteByIds(Arrays.asList(operIds));
    }

    @Override
    public int cleanOlderThan(int retentionMonths) {
        if (retentionMonths <= 0) {
            log.warn("[GZ-SYS-006] retentionMonths={} 无效，跳过清理", retentionMonths);
            return 0;
        }
        // 计算阈值日期：当前时间 - retentionMonths 个月
        LocalDateTime threshold = LocalDateTime.now().minusMonths(retentionMonths);
        Date thresholdDate = Date.from(threshold.atZone(ZoneId.systemDefault()).toInstant());
        LambdaQueryWrapper<GzSysOperLog> lqw = new LambdaQueryWrapper<GzSysOperLog>()
            .lt(GzSysOperLog::getOperTime, thresholdDate);
        int deleted = baseMapper.delete(lqw);
        log.info("[GZ-SYS-006] cleanOlderThan retentionMonths={} threshold={} deleted={}",
            retentionMonths, threshold, deleted);
        return deleted;
    }

    private LambdaQueryWrapper<GzSysOperLog> buildQueryWrapper(GzOperLogQueryBo query) {
        Map<String, Object> params = query.getParams() != null ? query.getParams() : Map.of();
        LambdaQueryWrapper<GzSysOperLog> lqw = new LambdaQueryWrapper<GzSysOperLog>()
            .like(StringUtils.isNotBlank(query.getTitle()), GzSysOperLog::getTitle, query.getTitle())
            .like(StringUtils.isNotBlank(query.getOperName()), GzSysOperLog::getOperName, query.getOperName())
            .like(StringUtils.isNotBlank(query.getOperIp()), GzSysOperLog::getOperIp, query.getOperIp())
            .eq(query.getBusinessType() != null, GzSysOperLog::getBusinessType, query.getBusinessType())
            .eq(query.getStatus() != null, GzSysOperLog::getStatus, query.getStatus());

        // 时间区间（params.beginTime / params.endTime — addDateRange 注入）
        Object begin = params.get("beginTime");
        Object end = params.get("endTime");
        if (begin != null && end != null) {
            lqw.between(GzSysOperLog::getOperTime, parseDate(begin, true), parseDate(end, false));
        }
        return lqw;
    }

    /**
     * 容错解析时间参数（前端可能传 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss）。
     */
    private Date parseDate(Object raw, boolean isStart) {
        if (raw == null) {
            return null;
        }
        String s = raw.toString().trim();
        if (s.length() == 10) {
            // 仅日期 — 起始用 00:00:00 / 结束用 23:59:59
            LocalDate ld = LocalDate.parse(s);
            LocalDateTime ldt = LocalDateTime.of(ld, isStart ? LocalTime.MIN : LocalTime.MAX);
            return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
        }
        // 含时间
        LocalDateTime ldt = LocalDateTime.parse(s.replace(' ', 'T'));
        return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
    }
}
