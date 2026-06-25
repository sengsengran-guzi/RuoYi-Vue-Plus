package org.dromara.gz.common.cs.service.impl;

import cn.hutool.core.util.ObjectUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.common.cs.domain.bo.GzCustomerServiceConfigBo;
import org.dromara.gz.common.cs.domain.entity.GzCustomerServiceConfig;
import org.dromara.gz.common.cs.domain.vo.GzCustomerServiceConfigVO;
import org.dromara.gz.common.cs.mapper.GzCustomerServiceConfigMapper;
import org.dromara.gz.common.cs.service.IGzCustomerServiceConfigService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客服配置服务实现（GZ-SYS-004B，按租户单行）。
 *
 * <p>多租户由拦截器自动 append {@code WHERE tenant_id=?}，故 selectOne(null) 即取当前租户那行
 * （UNIQUE(tenant_id) 保证 ≤1 行）；INSERT 不显式赋 tenant_id（走 InjectionMetaObjectHandler 自动填充，
 * 强约束 #3）。存即 upsert：有行 updateById，无行 insert。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-004B)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzCustomerServiceConfigServiceImpl implements IGzCustomerServiceConfigService {

    private final GzCustomerServiceConfigMapper baseMapper;

    @Override
    public GzCustomerServiceConfigVO getConfig() {
        GzCustomerServiceConfig entity = baseMapper.selectOne(null);
        GzCustomerServiceConfigVO vo = new GzCustomerServiceConfigVO();
        vo.setWxKfId(entity == null ? "" : nz(entity.getWxKfId()));
        vo.setPhone(entity == null ? "" : nz(entity.getPhone()));
        vo.setWxId(entity == null ? "" : nz(entity.getWxId()));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveConfig(GzCustomerServiceConfigBo bo) {
        GzCustomerServiceConfig entity = baseMapper.selectOne(null);
        if (ObjectUtil.isNull(entity)) {
            entity = new GzCustomerServiceConfig();
            applyBo(entity, bo);
            baseMapper.insert(entity);
        } else {
            applyBo(entity, bo);
            baseMapper.updateById(entity);
        }
    }

    private void applyBo(GzCustomerServiceConfig entity, GzCustomerServiceConfigBo bo) {
        entity.setWxKfId(nz(bo.getWxKfId()));
        entity.setPhone(nz(bo.getPhone()));
        entity.setWxId(nz(bo.getWxId()));
    }

    /** null → 空串（VO/存储口径统一：三字段恒非 null）。 */
    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
