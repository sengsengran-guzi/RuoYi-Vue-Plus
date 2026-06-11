package org.dromara.gz.common.recycle.service.impl;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.UserService;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.recycle.domain.vo.RecycleBinItemVO;
import org.dromara.gz.common.recycle.domain.vo.RecycleRawRow;
import org.dromara.gz.common.recycle.mapper.GzRecycleBinMapper;
import org.dromara.gz.common.recycle.service.IGzRecycleBinService;
import org.dromara.gz.common.recycle.service.internal.RecycleEntityRegistry;
import org.dromara.gz.common.recycle.service.internal.RecycleEntityRegistry.Def;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 回收站 service 实现（GZ-ADMIN-108）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-108)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzRecycleBinServiceImpl implements IGzRecycleBinService {

    /** 已归档记录保留期（天）：超期且 archived_flag=1 才物理清理。 */
    private static final int RETENTION_DAYS = 30;

    private final GzRecycleBinMapper recycleBinMapper;
    private final UserService userService;

    @Override
    public TableDataInfo<RecycleBinItemVO> list(String entityType, String nameKeyword,
                                                String startTime, String endTime,
                                                int pageNum, int pageSize) {
        List<Def> defs = StrUtil.isBlank(entityType)
            ? RecycleEntityRegistry.all()
            : List.of(RecycleEntityRegistry.require(entityType));

        // 1. 各表查 del_flag='2' 行 → 统一 VO（先不填删除人，批量解析后回填）
        List<RecycleBinItemVO> all = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Def def : defs) {
            List<RecycleRawRow> rows = recycleBinMapper.selectDeleted(
                def.tableName(), def.nameColumn(), nameKeyword, startTime, endTime);
            for (RecycleRawRow r : rows) {
                RecycleBinItemVO vo = new RecycleBinItemVO();
                vo.setEntityType(def.entityType());
                vo.setEntityTypeLabel(def.label());
                vo.setEntityId(r.getId());
                vo.setEntityName(r.getEntityName());
                vo.setDeleteTime(r.getDeleteTime());
                vo.setDaysAgo(r.getDeleteTime() == null ? null : ChronoUnit.DAYS.between(r.getDeleteTime(), now));
                vo.setArchived(r.getArchivedFlag() != null && r.getArchivedFlag() == 1);
                // 临时把 operatorId 暂存到 deleteOperatorName，下一步批量替换为昵称
                vo.setDeleteOperatorName(r.getDeleteOperatorId() == null ? null : String.valueOf(r.getDeleteOperatorId()));
                all.add(vo);
            }
        }

        // 2. 批量解析删除人昵称（update_by → nickName）
        Set<Long> operatorIds = all.stream()
            .map(RecycleBinItemVO::getDeleteOperatorName)
            .filter(StrUtil::isNotBlank)
            .map(Long::valueOf)
            .collect(Collectors.toSet());
        if (!operatorIds.isEmpty()) {
            Map<Long, String> nickMap = userService.selectUserNicksByIds(new ArrayList<>(operatorIds));
            for (RecycleBinItemVO vo : all) {
                if (StrUtil.isNotBlank(vo.getDeleteOperatorName())) {
                    String nick = nickMap.get(Long.valueOf(vo.getDeleteOperatorName()));
                    vo.setDeleteOperatorName(StrUtil.isBlank(nick) ? vo.getDeleteOperatorName() : nick);
                }
            }
        }

        // 3. 按删除时间倒序 + 内存分页
        all.sort(Comparator.comparing(RecycleBinItemVO::getDeleteTime,
            Comparator.nullsLast(Comparator.reverseOrder())));
        long total = all.size();
        int from = Math.max(0, (pageNum - 1) * pageSize);
        int to = Math.min(all.size(), from + pageSize);
        List<RecycleBinItemVO> pageRows = from >= all.size() ? List.of() : all.subList(from, to);

        TableDataInfo<RecycleBinItemVO> rsp = TableDataInfo.build();
        rsp.setRows(new ArrayList<>(pageRows));
        rsp.setTotal(total);
        return rsp;
    }

    @Override
    public String restore(String entityType, Long entityId) {
        Def def = RecycleEntityRegistry.require(entityType);
        String name = resolveName(def, entityId);
        int affected = recycleBinMapper.restore(def.tableName(), entityId);
        if (affected == 0) {
            throw new ServiceException("恢复失败：记录不存在或不在回收站（del_flag 非 '2'）");
        }
        log.info("[GZ-RECYCLE] restore {} id={} name={}", def.tableName(), entityId, name);
        return name;
    }

    @Override
    public String archive(String entityType, Long entityId) {
        Def def = RecycleEntityRegistry.require(entityType);
        String name = resolveName(def, entityId);
        int affected = recycleBinMapper.archive(def.tableName(), entityId);
        if (affected == 0) {
            throw new ServiceException("归档失败：记录不存在或不在回收站（del_flag 非 '2'）");
        }
        log.info("[GZ-RECYCLE] archive {} id={} name={}", def.tableName(), entityId, name);
        return name;
    }

    @Override
    public int cleanupExpired() {
        return TenantHelper.ignore(() -> {
            LocalDateTime before = LocalDateTime.now().minusDays(RETENTION_DAYS);
            int total = 0;
            for (Def def : RecycleEntityRegistry.all()) {
                int n = recycleBinMapper.cleanupExpired(def.tableName(), before);
                total += n;
                if (n > 0) {
                    log.info("[GZ-RECYCLE-CLEANUP] {} 物理清理 {} 行（archived 且 update_time < {}）",
                        def.tableName(), n, before);
                }
            }
            log.info("[GZ-RECYCLE-CLEANUP] 完成，共物理删除 {} 行", total);
            return total;
        });
    }

    /** 解析记录展示名（查不到返回 id 文本，restore/archive 写日志用，非业务校验）。 */
    private String resolveName(Def def, Long entityId) {
        List<RecycleRawRow> rows = recycleBinMapper.selectDeleted(def.tableName(), def.nameColumn(), null, null, null);
        return rows.stream()
            .filter(r -> entityId.equals(r.getId()))
            .map(RecycleRawRow::getEntityName)
            .findFirst()
            .orElse(String.valueOf(entityId));
    }
}
