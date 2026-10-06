package org.dromara.gz.jp.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.jp.domain.bo.GzJpSnapTermBo;
import org.dromara.gz.jp.domain.entity.GzJpSnapTerm;
import org.dromara.gz.jp.mapper.GzJpSnapTermMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class GzJpSnapTermService {
    private final GzJpSnapTermMapper mapper;
    private final GzJpSnapNormalizer normalizer;

    public TableDataInfo<GzJpSnapTerm> list(String keyword, String termType, Boolean enabled, PageQuery page) {
        var query = new LambdaQueryWrapper<GzJpSnapTerm>();
        if (keyword != null && !keyword.isBlank()) query.and(q -> q.like(GzJpSnapTerm::getZhTerm, keyword.trim()).or().like(GzJpSnapTerm::getJaTerm, keyword.trim()));
        if (termType != null && !termType.isBlank()) query.eq(GzJpSnapTerm::getTermType, termType);
        if (enabled != null) query.eq(GzJpSnapTerm::getEnabled, enabled);
        query.orderByAsc(GzJpSnapTerm::getSortNo).orderByAsc(GzJpSnapTerm::getId);
        return TableDataInfo.build(mapper.selectPage(page.build(), query));
    }

    @Transactional(rollbackFor = Exception.class)
    public Long add(GzJpSnapTermBo bo) {
        var row = entity(bo);
        // The unique key excludes del_flag. Reuse a deleted row instead of accumulating tombstones.
        var deleted = mapper.findIncludingDeleted(row.getZhTerm());
        if (deleted != null) {
            if ("0".equals(deleted.getDelFlag())) throw duplicate();
            row.setId(deleted.getId());
            row.setUpdateBy(org.dromara.common.satoken.utils.LoginHelper.getUserId());
            if (mapper.restore(row) != 1) throw duplicate();
        } else {
            try { mapper.insert(row); }
            catch (DuplicateKeyException ex) { throw duplicate(); }
        }
        return row.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void edit(Long id, GzJpSnapTermBo bo) {
        require(id);
        var row = entity(bo); row.setId(id);
        try { if (mapper.updateById(row) != 1) throw new ServiceException("词条不存在或已删除"); }
        catch (DuplicateKeyException ex) { throw duplicate(); }
    }

    @Transactional(rollbackFor = Exception.class)
    public void enabled(Long id, Boolean enabled) {
        require(id);
        var row = new GzJpSnapTerm(); row.setId(id); row.setEnabled(enabled);
        if (mapper.updateById(row) != 1) throw new ServiceException("词条不存在或已删除");
    }

    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        if (mapper.deleteById(id) != 1) throw new ServiceException("词条不存在或已删除");
    }

    public GzJpSnapNormalizer.Result normalize(String q) {
        // Same normalizer and live DB dictionary as the miniapp. No dictionary cache to evict.
        return normalizer.normalize(q, mapper.selectList(new LambdaQueryWrapper<GzJpSnapTerm>()
            .eq(GzJpSnapTerm::getEnabled, true).orderByAsc(GzJpSnapTerm::getSortNo).orderByAsc(GzJpSnapTerm::getId)));
    }
    private void require(Long id) { if (mapper.selectById(id) == null) throw new ServiceException("词条不存在或已删除"); }
    private ServiceException duplicate() { return new ServiceException("中文词已存在，请修改现有词条"); }
    private GzJpSnapTerm entity(GzJpSnapTermBo bo) {
        var row = new GzJpSnapTerm(); row.setZhTerm(bo.zhTerm().trim()); row.setJaTerm(bo.jaTerm().trim());
        row.setTermType(bo.termType()); row.setEnabled(bo.enabled()); row.setSortNo(bo.sortNo()); return row;
    }
}
