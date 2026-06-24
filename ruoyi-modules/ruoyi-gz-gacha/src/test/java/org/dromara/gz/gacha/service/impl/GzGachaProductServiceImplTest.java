package org.dromara.gz.gacha.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.gacha.domain.bo.GzGachaProductBo;
import org.dromara.gz.gacha.domain.entity.GzGachaProduct;
import org.dromara.gz.gacha.mapper.GzGachaPrizeMapper;
import org.dromara.gz.gacha.mapper.GzGachaProductMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GzGachaProductServiceImpl 单测（ADR-0013 / GZ-GACHA-112 — 产品 CRUD + 引用守卫 + 编号 + 下拉/批量取）。
 *
 * <p>覆盖：product_no GPRD- 前缀生成 + 同日序号递增；同名允许（product_no 不同）；删除引用守卫
 * （被投放线引用拒删）；更新不存在 → 抛；listOptions 仅 enabled=1；mapByIds 批量取。全 Mockito，无 DB。</p>
 */
@Tag("dev")
@DisplayName("GzGachaProductServiceImpl 单测 — 产品 CRUD / 引用守卫 / 编号")
@ExtendWith(MockitoExtension.class)
class GzGachaProductServiceImplTest {

    @Mock
    private GzGachaProductMapper productMapper;
    @Mock
    private GzGachaPrizeMapper prizeMapper;

    private GzGachaProductServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzGachaProductServiceImpl(productMapper, prizeMapper);
    }

    private GzGachaProductBo baseBo() {
        GzGachaProductBo bo = new GzGachaProductBo();
        bo.setName("CHIIKAWA 立牌");
        bo.setImageId(7001L);
        bo.setReferenceValueCent(29900L);
        bo.setIpTag("CHIIKAWA");
        return bo;
    }

    @Test
    @DisplayName("新建：生成 GPRD- 前缀 product_no 且持久化字段（enabled 默认 1）")
    void insertByBo_generatesProductNo_andPersists() {
        when(productMapper.selectOne(any())).thenReturn(null); // 当日无既有 → 序号 1
        when(productMapper.insert(any(GzGachaProduct.class))).thenAnswer(inv -> {
            ((GzGachaProduct) inv.getArgument(0)).setId(50L);
            return 1;
        });

        ArgumentCaptor<GzGachaProduct> cap = ArgumentCaptor.forClass(GzGachaProduct.class);
        Long id = service.insertByBo(baseBo());

        assertEquals(50L, id);
        verify(productMapper).insert(cap.capture());
        GzGachaProduct saved = cap.getValue();
        assertTrue(saved.getProductNo().startsWith("GPRD-"), "product_no GPRD- 前缀");
        assertTrue(saved.getProductNo().endsWith("-000001"), "当日无既有 → 序号 000001");
        assertEquals("CHIIKAWA 立牌", saved.getName());
        assertEquals(7001L, saved.getImageId());
        assertEquals(29900L, saved.getReferenceValueCent());
        assertEquals(1, saved.getEnabled(), "enabled 空默认 1");
        assertEquals(0, saved.getVersion());
    }

    @Test
    @DisplayName("同名允许（不去重）：两次同名建产品，product_no 不同（序号递增）")
    void insertByBo_dupName_allowed_diffNo() {
        String today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        GzGachaProduct last = new GzGachaProduct();
        last.setProductNo("GPRD-" + today + "-000003");
        when(productMapper.selectOne(any())).thenReturn(last);
        when(productMapper.insert(any(GzGachaProduct.class))).thenReturn(1);

        ArgumentCaptor<GzGachaProduct> cap = ArgumentCaptor.forClass(GzGachaProduct.class);
        service.insertByBo(baseBo()); // 同名

        verify(productMapper).insert(cap.capture());
        assertTrue(cap.getValue().getProductNo().endsWith("-000004"), "同名仍各得唯一 product_no（序号递增）");
    }

    @Test
    @DisplayName("删除引用守卫：产品被投放线引用 → 抛 ServiceException「产品已被机器投放」")
    void deleteByIds_blockedWhenReferencedByPrize() {
        when(prizeMapper.selectCount(any())).thenReturn(2L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.deleteByIds(List.of(100L)));
        assertTrue(ex.getMessage().contains("已被机器投放"), "拒删 msg");
        verify(productMapper, never()).deleteByIds(any());
    }

    @Test
    @DisplayName("删除：无投放线引用 → 软删成功")
    void deleteByIds_okWhenNotReferenced() {
        when(prizeMapper.selectCount(any())).thenReturn(0L);
        when(productMapper.deleteByIds(any())).thenReturn(1);

        boolean ok = service.deleteByIds(List.of(100L));

        assertTrue(ok);
        verify(productMapper, times(1)).deleteByIds(any());
    }

    @Test
    @DisplayName("更新：不存在 id → 抛 ServiceException（不 update）")
    void updateByBo_notFound() {
        when(productMapper.selectById(999L)).thenReturn(null);
        GzGachaProductBo bo = baseBo();
        bo.setId(999L);

        assertThrows(ServiceException.class, () -> service.updateByBo(bo));
        verify(productMapper, never()).updateById(any(GzGachaProduct.class));
    }

    @Test
    @DisplayName("listOptions：仅 enabled=1（停用产品不返回）—— 由 LambdaQuery 条件保证")
    void listOptions_onlyEnabled() {
        GzGachaProduct enabled = new GzGachaProduct();
        enabled.setId(100L);
        enabled.setName("可投放产品");
        enabled.setEnabled(1);
        // mapper 已按 enabled=1 过滤（service 用 LambdaQuery 限定）→ 这里只返回 enabled=1 的
        when(productMapper.selectList(any())).thenReturn(List.of(enabled));

        var options = service.listOptions(null);

        assertEquals(1, options.size());
        assertEquals(100L, options.get(0).getId());
        assertEquals(1, options.get(0).getEnabled());
    }

    @Test
    @DisplayName("mapByIds：批量取产品 → id → entity；空入参短路不查库")
    void mapByIds_batchAndShortCircuit() {
        assertTrue(service.mapByIds(List.of()).isEmpty());
        verify(productMapper, never()).selectByIds(any());

        GzGachaProduct p1 = new GzGachaProduct();
        p1.setId(100L);
        GzGachaProduct p2 = new GzGachaProduct();
        p2.setId(101L);
        when(productMapper.selectByIds(any())).thenReturn(List.of(p1, p2));

        Map<Long, GzGachaProduct> map = service.mapByIds(List.of(100L, 101L));
        assertEquals(2, map.size());
        assertEquals(100L, map.get(100L).getId());
        assertEquals(101L, map.get(101L).getId());
    }

    @Test
    @DisplayName("selectAdminById：不存在 → null")
    void selectAdminById_notFound() {
        when(productMapper.selectById(9L)).thenReturn(null);
        assertEquals(null, service.selectAdminById(9L));
    }

    @Test
    @DisplayName("分页：name 模糊 + ipTag + enabled 筛选 → VO 映射")
    void selectAdminPage_mapsVo() {
        GzGachaProduct p = new GzGachaProduct();
        p.setId(100L);
        p.setProductNo("GPRD-20260630-000001");
        p.setName("CHIIKAWA 立牌");
        p.setImageId(7001L);
        p.setReferenceValueCent(29900L);
        p.setIpTag("CHIIKAWA");
        p.setEnabled(1);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<GzGachaProduct> dbPage =
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20, 1);
        dbPage.setRecords(List.of(p));
        when(productMapper.selectPage(any(), any())).thenReturn(dbPage);

        var result = service.selectAdminPage(new org.dromara.gz.gacha.domain.bo.GzGachaProductQueryBo(),
            new org.dromara.common.mybatis.core.page.PageQuery(1, 20));

        assertEquals(1, result.getRows().size());
        var vo = result.getRows().get(0);
        assertEquals(100L, vo.getId());
        assertEquals("CHIIKAWA 立牌", vo.getName());
        assertEquals(7001L, vo.getImageId());
        assertEquals(29900L, vo.getReferenceValueCent());
        assertFalse(result.getRows().isEmpty());
    }
}
